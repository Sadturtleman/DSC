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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NameNode에서 최신 FSImage를 다운로드하고, OIV로 파싱해 메타데이터를 반환한다.
 *
 * <p>수정 사항: Hadoop FSImage XML 구조 특성상 {@code <inode>} 안에는 전체 경로가 없습니다.
 * 따라서 {@code <INodeDirectorySection>}의 부모-자식 관계를 매핑하여 메모리에서
 * 전체 경로(Path)를 재조립(Reconstruct)하도록 수정되었습니다.
 */
public class FsImageFetcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FsImageFetcher.class);

    private final Configuration hadoopConf;
    private final Path localFsimageDir;

    public FsImageFetcher(String confDir, String localFsimageDir) throws IOException {
        this.hadoopConf = buildConf(confDir);
        this.localFsimageDir = Paths.get(localFsimageDir);
        Files.createDirectories(this.localFsimageDir);
    }

    public List<FileMetadata> fetchAndParse() throws IOException {
        Path imageFile = downloadFsImage();
        log.info("FSImage 다운로드 완료: {}", imageFile);
        return parseWithOiv(imageFile);
    }

    Path downloadFsImage() throws IOException {
        DFSAdmin admin = new DFSAdmin(hadoopConf);
        String[] args = {"-fetchImage", localFsimageDir.toString()};
        int exitCode;
        try {
            exitCode = ToolRunner.run(admin, args);
        } catch (Exception e) {
            throw new IOException("DFSAdmin.fetchImage 실행 중 예외", e);
        }
        if (exitCode != 0) {
            throw new IOException("FSImage 다운로드 실패 (exitCode=" + exitCode + ")");
        }
        return findLatestFsImageFile();
    }

    private Path findLatestFsImageFile() throws IOException {
        try (var stream = Files.list(localFsimageDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("fsimage_\\d+"))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElseThrow(() -> new IOException("fsimage 파일을 찾을 수 없음"));
        }
    }

    List<FileMetadata> parseWithOiv(Path imageFile) throws IOException {
        PipedOutputStream sink = new PipedOutputStream();
        PipedInputStream source = new PipedInputStream(sink, 5 * 1024 * 1024);

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

        List<FileMetadata> result;
        try (source) {
            result = parseXmlAndReconstructPaths(source);
        } finally {
            try {
                oivThread.join(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    /**
     * XML을 파싱하면서 Inode 속성과 Directory 관계를 수집한 뒤 조합한다.
     */
    private List<FileMetadata> parseXmlAndReconstructPaths(InputStream xmlStream) {
        Map<Long, InodeRecord> inodes = new HashMap<>();
        Map<Long, Long> childToParent = new HashMap<>();
        
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try {
            XMLStreamReader xsr = factory.createXMLStreamReader(xmlStream);
            String currentTag = null;
            boolean inInode = false;
            boolean inDirectory = false;
            
            long currentId = 0;
            String currentType = null;
            String currentName = null;
            long currentSize = 0, currentAtime = 0, currentMtime = 0;
            int currentPolicy = 0;
            
            long dirParentId = 0;

            while (xsr.hasNext()) {
                int event = xsr.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentTag = xsr.getLocalName();
                    if ("inode".equals(currentTag)) {
                        if (!inDirectory) {
                            inInode = true;
                            currentId = 0; currentType = null; currentName = null;
                            currentSize = 0; currentAtime = 0; currentMtime = 0; currentPolicy = 0;
                        }
                    } else if ("directory".equals(currentTag)) {
                        inDirectory = true;
                        dirParentId = 0;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    String text = xsr.getText().trim();
                    if (text.isEmpty()) continue;
                    
                    if (inInode) {
                        switch (currentTag) {
                            case "id": currentId = Long.parseLong(text); break;
                            case "type": currentType = text; break;
                            case "name": currentName = text; break;
                            case "fileSize": currentSize = Long.parseLong(text); break;
                            case "atime": currentAtime = Long.parseLong(text); break;
                            case "mtime": currentMtime = Long.parseLong(text); break;
                            case "storagePolicy": currentPolicy = Integer.parseInt(text); break;
                        }
                    } else if (inDirectory) {
                        if ("parent".equals(currentTag)) {
                            dirParentId = Long.parseLong(text);
                        } else if ("child".equals(currentTag) || "inode".equals(currentTag)) {
                            long childId = Long.parseLong(text);
                            childToParent.put(childId, dirParentId);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String endTag = xsr.getLocalName();
                    if ("inode".equals(endTag) && inInode) {
                        inodes.put(currentId, new InodeRecord(currentId, currentType, currentName, currentSize, currentAtime, currentMtime, currentPolicy));
                        inInode = false;
                    } else if ("directory".equals(endTag)) {
                        inDirectory = false;
                    }
                    currentTag = null;
                }
            }
            xsr.close();
            
            // Reconstruct full paths for FILE types
            List<FileMetadata> files = new ArrayList<>();
            for (InodeRecord rec : inodes.values()) {
                if ("FILE".equals(rec.type)) {
                    String fullPath = buildPath(rec.id, inodes, childToParent);
                    files.add(new FileMetadata(fullPath, rec.size, rec.atime, rec.mtime, rec.policy));
                }
            }
            
            log.info("경로 재조립 완료. 수집된 파일 수={}", files.size());
            return files;

        } catch (Exception e) {
            log.warn("XML 파싱 예외 발생: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String buildPath(long id, Map<Long, InodeRecord> inodes, Map<Long, Long> childToParent) {
        StringBuilder sb = new StringBuilder();
        long curr = id;
        while (curr != 0) {
            InodeRecord rec = inodes.get(curr);
            if (rec != null && rec.name != null && !rec.name.isEmpty()) {
                sb.insert(0, "/" + rec.name);
            }
            curr = childToParent.getOrDefault(curr, 0L);
        }
        String path = sb.toString();
        return path.isEmpty() ? "/" : path;
    }

    private static class InodeRecord {
        long id; String type, name;
        long size, atime, mtime; int policy;
        InodeRecord(long id, String type, String name, long size, long atime, long mtime, int policy) {
            this.id = id; this.type = type; this.name = name; this.size = size;
            this.atime = atime; this.mtime = mtime; this.policy = policy;
        }
    }

    private static Configuration buildConf(String confDir) {
        Configuration conf = new Configuration();
        if (confDir != null && !confDir.isBlank()) {
            conf.addResource(new org.apache.hadoop.fs.Path(confDir + "/core-site.xml"));
            conf.addResource(new org.apache.hadoop.fs.Path(confDir + "/hdfs-site.xml"));
        }
        return conf;
    }

    @Override
    public void close() {}
}
