package edu.dsc.tiering.hdfs;

import edu.dsc.tiering.scoring.FileMetadata;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.apache.hadoop.hdfs.tools.offlineImageViewer.PBImageXmlWriter;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * NameNode에서 최신 FSImage를 HTTP로 다운로드하고,
 * OIV(OfflineImageViewer)로 파싱해 파일 메타데이터 목록을 반환한다.
 *
 * <p>동작 순서:
 * <ol>
 *   <li>{@code DFSAdmin -fetchImage}로 FSImage를 로컬 디렉토리에 다운로드</li>
 *   <li>{@code PBImageXmlWriter}로 protobuf 바이너리 → XML 변환 (인메모리 pipe)</li>
 *   <li>XML 스트리밍 파싱으로 inode 메타데이터 추출</li>
 * </ol>
 *
 * <p>설계 결정:
 * <ul>
 *   <li>OIV를 별도 프로세스(subprocess)가 아닌 API로 직접 호출 — JVM 기동 비용 없음</li>
 *   <li>XML을 파일로 쓰지 않고 PipedStream으로 연결 — 대용량 클러스터에서 디스크 I/O 절감</li>
 *   <li>파싱 중 예외가 발생해도 이미 수집한 메타데이터는 반환 (부분 결과 허용)</li>
 * </ul>
 */
public class FsImageFetcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FsImageFetcher.class);

    /** OIV XML에서 파일 항목을 나타내는 태그 */
    private static final String TAG_INODE    = "inode";
    private static final String TAG_TYPE     = "type";
    private static final String TAG_NAME     = "name";
    private static final String TAG_PATH     = "path";       // OIV가 계산한 전체 경로
    private static final String TAG_REPL     = "replication";
    private static final String TAG_MTIME    = "mtime";
    private static final String TAG_ATIME    = "atime";
    private static final String TAG_SIZE     = "preferredBlockSize"; // 블록 단위 크기
    private static final String TAG_FILESIZE = "fileSize";           // 실제 파일 크기
    private static final String TAG_STORAGEPOLICY = "storagePolicy";
    private static final String INODE_TYPE_FILE = "FILE";

    private final Configuration hadoopConf;
    private final Path           localFsimageDir;

    /**
     * @param confDir         HADOOP_CONF_DIR 경로 (null이면 클래스패스 자동 로드)
     * @param localFsimageDir FSImage를 저장할 로컬 디렉토리
     */
    public FsImageFetcher(String confDir, String localFsimageDir) throws IOException {
        this.hadoopConf    = buildConf(confDir);
        this.localFsimageDir = Paths.get(localFsimageDir);
        Files.createDirectories(this.localFsimageDir);
    }

    // ── 공개 API ────────────────────────────────────────────────────────

    /**
     * NameNode에서 최신 FSImage를 다운로드하고 파일 메타데이터 목록을 반환한다.
     *
     * @return HDFS FILE 타입 inode 목록 (디렉토리·심볼릭링크 제외)
     * @throws IOException NameNode 연결 실패 또는 파싱 오류
     */
    public List<FileMetadata> fetchAndParse() throws IOException {
        Path imageFile = downloadFsImage();
        log.info("FSImage 다운로드 완료: {}", imageFile);
        return parseWithOiv(imageFile);
    }

    // ── 1단계: fetchImage ────────────────────────────────────────────────

    /**
     * {@code hdfs dfsadmin -fetchImage <dir>}와 동일하게 동작한다.
     * 다운로드 후 가장 최근에 생성된 fsimage_* 파일 경로를 반환한다.
     */
    Path downloadFsImage() throws IOException {
        DFSAdmin admin = new DFSAdmin(hadoopConf);
        String[] args = {"-fetchImage", localFsimageDir.toString()};

        int exitCode;
        try {
            exitCode = ToolRunner.run(admin, args);
        } catch (Exception e) {
            throw new IOException("DFSAdmin.fetchImage 실행 중 예외 발생", e);
        }

        if (exitCode != 0) {
            throw new IOException("FSImage 다운로드 실패 (exitCode=" + exitCode + ")");
        }

        // 다운로드된 파일 중 가장 최신 fsimage 파일 선택
        return findLatestFsImageFile();
    }

    /** localFsimageDir에서 가장 최근 수정된 fsimage_XXXXXXXXXX 파일을 찾는다 */
    private Path findLatestFsImageFile() throws IOException {
        try (var stream = Files.list(localFsimageDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("fsimage_\\d+"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a)
                                        .compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElseThrow(() -> new IOException(
                            "fsimage 파일을 찾을 수 없음: " + localFsimageDir));
        }
    }

    // ── 2단계: OIV 파싱 (pipe 방식) ──────────────────────────────────────

    /**
     * PBImageXmlWriter로 FSImage protobuf를 XML로 변환하면서,
     * PipedStream으로 연결된 XML 파서가 동시에 메타데이터를 추출한다.
     *
     * <p>대용량 FSImage(수 GB)에서 전체 XML을 디스크에 쓰지 않아도 된다.
     */
    List<FileMetadata> parseWithOiv(Path imageFile) throws IOException {
        PipedOutputStream sink   = new PipedOutputStream();
        PipedInputStream  source = new PipedInputStream(sink, 1 << 20); // 1MB 버퍼

        // OIV 변환 스레드
        Thread oivThread = new Thread(() -> {
            try (PrintStream ps = new PrintStream(sink)) {
                PBImageXmlWriter writer = new PBImageXmlWriter(hadoopConf, ps);
                writer.visit(new RandomAccessFile(imageFile.toFile(), "r"));
            } catch (Exception e) {
                log.error("OIV 변환 오류: {}", e.getMessage(), e);
            }
        }, "oiv-converter");
        oivThread.setDaemon(true);
        oivThread.start();

        // XML 파싱 (메인 스레드)
        List<FileMetadata> result;
        try (source) {
            result = parseXml(source);
        } finally {
            try {
                oivThread.join(60_000); // 최대 60초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("FSImage 파싱 완료. 파일 수={}", result.size());
        return result;
    }

    /** XML 스트림에서 FILE 타입 inode만 추출한다 */
    private List<FileMetadata> parseXml(InputStream xmlStream) throws IOException {
        List<FileMetadata> files = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // XXE 방지
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try {
            XMLStreamReader xsr = factory.createXMLStreamReader(xmlStream);
            boolean inInode = false;
            String  type    = null;
            String  path    = null;
            long    size    = 0;
            long    atime   = 0;
            long    mtime   = 0;
            int     storagePolicy = 0; // 0 = unset (inherited)
            String  currentTag    = null;

            while (xsr.hasNext()) {
                int event = xsr.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentTag = xsr.getLocalName();
                        if (TAG_INODE.equals(currentTag)) {
                            inInode = true;
                            // 초기화
                            type = null; path = null; size = 0; atime = 0;
                            mtime = 0; storagePolicy = 0;
                        }
                        break;
                    case XMLStreamConstants.CHARACTERS:
                        if (!inInode || currentTag == null) break;
                        String text = xsr.getText().trim();
                        if (text.isEmpty()) break;
                        if (TAG_TYPE.equals(currentTag))          type = text;
                        else if (TAG_PATH.equals(currentTag))     path = text;
                        else if (TAG_FILESIZE.equals(currentTag)) size = parseLong(text);
                        else if (TAG_ATIME.equals(currentTag))    atime = parseLong(text);
                        else if (TAG_MTIME.equals(currentTag))    mtime = parseLong(text);
                        else if (TAG_STORAGEPOLICY.equals(currentTag)) storagePolicy = parseInt(text);
                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        if (TAG_INODE.equals(xsr.getLocalName()) && inInode) {
                            inInode = false;
                            if (INODE_TYPE_FILE.equals(type) && path != null && size >= 0) {
                                files.add(new FileMetadata(
                                        path, size, atime, mtime, storagePolicy));
                            }
                            currentTag = null;
                        }
                        break;
                    default:
                        break;
                }
            }
            xsr.close();
        } catch (Exception e) {
            // 파싱 도중 파이프가 끊기는 경우도 있으므로 경고만 남기고 부분 결과 반환
            log.warn("XML 파싱 중 예외 (부분 결과 반환): {}", e.getMessage());
        }
        return files;
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    private static Configuration buildConf(String confDir) {
        Configuration conf = new Configuration();
        if (confDir != null && !confDir.isBlank()) {
            conf.addResource(new org.apache.hadoop.fs.Path(confDir + "/core-site.xml"));
            conf.addResource(new org.apache.hadoop.fs.Path(confDir + "/hdfs-site.xml"));
        }
        return conf;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    @Override
    public void close() {
        // Hadoop FileSystem 캐시는 JVM 종료 시 정리됨; 여기서는 별도 리소스 없음
    }
}
