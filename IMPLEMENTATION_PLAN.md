# DỰ ÁN MYDRIVE-SYNC – CHUNK-BASED BACKUP & ROLLBACK

Tôi đang phát triển dự án MyDrive-Sync — ứng dụng đồng bộ file cho doanh nghiệp, viết bằng Java 17, Maven, TCP Socket (plain), database MySQL.

Nhiệm vụ duy nhất: tối ưu hóa chức năng Backup và Rollback file.

Tuyệt đối KHÔNG được can thiệp vào:
- TLS hoặc bổ sung TLS.
- NetworkClient phần xử lý kết nối.
- ServerCore phần accept socket.
- Các chức năng UI không liên quan.
- Những logic hiện có không liên quan đến Backup/Rollback.

---

## 1. Nhánh Git hiện tại

Nhánh làm việc dự kiến:

`feature/chunk-backup-rollback`

Trước khi làm bất cứ việc gì, hãy kiểm tra:
- Nhánh Git hiện tại bằng `git branch --show-current`.
- Trạng thái working tree bằng `git status`.
- Xác nhận bạn đang làm việc trên nhánh `feature/chunk-backup-rollback`.

Không tự ý chuyển nhánh, tạo nhánh, checkout, reset, revert hoặc thực hiện các thao tác Git làm thay đổi lịch sử hay trạng thái dự án.

Nếu nhánh không đúng hoặc working tree có thay đổi chưa được xác định, hãy dừng lại và báo cho tôi.

Nhánh `basic-features` là nhánh gốc để tham chiếu. Không được làm thay đổi nhánh này.

---

## 2. Cấu trúc file hiện có

`src/main/java/vn/edu/pbl4sync/`

- client/
  - AgentService.java
  - ClientConfig.java
  - ClientMain.java
  - NetworkClient.java
  - sync/
    - FolderWatcher.java
    - IgnoreRegistry.java
    - LocalStateStore.java
    - SyncFileFilter.java
  - ui/ (nhiều file UI, không cần sửa)

- common/
  - HashUtil.java
  - MessageTypes.java
  - Packet.java
  - ProtocolIO.java
  - ReceivedPacket.java
  - TableCodec.java

- server/
  - ClientHandler.java
  - ClientSession.java
  - ConnectionManager.java
  - ServerConfig.java
  - ServerCore.java
  - ServerLogListener.java
  - ServerMain.java
  - db/
    - Database.java
    - SimpleConnectionPool.java
  - ui/ (không cần sửa)

Lưu ý: Đây là cấu trúc đã biết. Hãy kiểm tra source thực tế trước khi kết luận hoặc triển khai.

---

## 3. Hiện trạng và mục tiêu

Hiện tại:
- Chưa có bảng file_versions, chunks, file_version_chunks.
- Chưa có LocalBackupStore.java.
- Chưa có RabinChunker.java, ChunkManifest.java, ChunkStore.java.
- MessageTypes chưa có CHUNK_QUERY, CHUNK_QUERY_RESULT, CHUNK_UPLOAD.
- Packet.java dùng Map<String, String> để truyền data.
- Giao tiếp hiện tại dùng plain Socket và ServerSocket.

Hãy xác minh lại các thông tin này từ source code và database initialization hiện có.

---

## 4. Năm thuật toán cần triển khai

### 4.1. Content-Defined Chunking (CDC) dùng Rabin Fingerprint

- Cắt file thành chunk dựa trên nội dung thực tế.
- WINDOW = 64 byte.
- MASK_TARGET = (1L << 22) - 1.
- Kích thước chunk trung bình khoảng 4MB.
- MIN_SIZE = 1MB.
- MAX_SIZE = 16MB.
- Dùng bảng tra cứu OUT_TABLE và MOD_TABLE được tính trước.
- Điểm cắt tự nhiên ổn định khi file bị sửa.

POLY = 0x3DA3358B4DC173L.

### 4.2. SHA-256

- Mỗi chunk được định danh bằng SHA-256 hash, 64 ký tự hex.
- Hash giống nhau nghĩa là nội dung giống nhau.
- Sử dụng java.security.MessageDigest.
- Không dùng thư viện ngoài.

### 4.3. Deduplication

- Mỗi chunk chỉ lưu một lần trong bảng chunks.
- hash là PRIMARY KEY.
- INSERT IGNORE để bỏ qua chunk đã tồn tại.
- Nhiều file/version có thể dùng chung chunk.

### 4.4. Delta Upload

- Client chia file thành chunk.
- Tính SHA-256 từng chunk.
- Tạo manifest.
- Gửi CHUNK_QUERY lên Server.
- Server kiểm tra chunk đã tồn tại.
- Server trả về danh sách hash còn thiếu.
- Client chỉ upload chunk còn thiếu.
- Sau khi upload đủ, Client gửi FILE_EVENT kèm manifest text.

### 4.5. Snapshot Manifest và Direct Assembly

- Mỗi version lưu danh sách hash theo thứ tự.
- Rollback đọc manifest và ghép chunk từ chunk store.
- Không cần đi qua các version trung gian.

---

## 5. Các quyết định đã thống nhất

1. Nâng cấp đồng loạt Client và Server, không cần tương thích Client cũ.
2. Không thực hiện hoặc bổ sung TLS.
3. Manifest dùng text thuần tùy chỉnh và gửi toàn bộ trong một request.
4. Không cần migrate dữ liệu cũ.
5. Local Backup lưu manifest cho tất cả version và full copy cho version gần nhất.
6. Bắt đầu triển khai ngay sau khi hoàn tất kiểm tra và xác nhận phạm vi.

---

## 6. Schema database cần thêm

### Bảng file_versions

- id BIGINT PK AUTO_INCREMENT
- file_id BIGINT FK → files(id) ON DELETE CASCADE
- version BIGINT NOT NULL
- base_version BIGINT NOT NULL
- checksum VARCHAR(128)
- manifest TEXT
- is_deleted BOOLEAN DEFAULT FALSE
- source_user_id BIGINT FK → users(id)
- source_device VARCHAR(180)
- operation VARCHAR(30)
- rollback_from_version BIGINT
- created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
- UNIQUE KEY (file_id, version)

### Bảng chunks

- hash VARCHAR(64) PK
- size INT NOT NULL
- content LONGBLOB NOT NULL
- created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

### Bảng file_version_chunks

- file_id BIGINT NOT NULL
- version BIGINT NOT NULL
- chunk_order INT NOT NULL
- chunk_hash VARCHAR(64) FK → chunks(hash)
- chunk_size INT NOT NULL
- PRIMARY KEY (file_id, version, chunk_order)
- FK file_id → files(id) ON DELETE CASCADE
- FK chunk_hash → chunks(hash)

Trước khi triển khai schema, phải kiểm tra cấu trúc thực tế của các bảng files và users, kiểu dữ liệu khóa ngoại, cách Database.initializeSchema() đang hoạt động.

Nếu schema đề xuất không tương thích với database hiện tại, hãy báo cáo và đề xuất điều chỉnh trước khi sửa Database.java.

---

## 7. Message type cần bổ sung

### CHUNK_QUERY
Client → Server

Fields:
- workspaceId
- path
- checksum
- size
- baseVersion
- manifest

### CHUNK_QUERY_RESULT
Server → Client

Fields:
- missingHashes (CSV)

### CHUNK_UPLOAD
Client → Server

Fields:
- hash
- size

Payload: binary content của một chunk.

Ngoài ra cần:
- FILE_VERSIONS_REQUEST
- FILE_ROLLBACK_REQUEST

Hãy kiểm tra cách MessageTypes, Packet, ReceivedPacket và ProtocolIO đang hoạt động để thiết kế tương thích với framing hiện tại.

Không được tự ý thay đổi cơ chế kết nối hoặc framing chung nếu không thực sự cần thiết.

---

## 8. Luồng Delta Upload

Khi Client phát hiện file thay đổi:

1. RabinChunker.chunk(file).
2. ChunkManifest.from(chunks, size).
3. Gửi CHUNK_QUERY kèm manifest.
4. Server kiểm tra bảng chunks.
5. Server trả CHUNK_QUERY_RESULT.
6. Client upload từng chunk còn thiếu qua CHUNK_UPLOAD.
7. Server xác minh SHA-256 trước khi lưu.
8. Client gửi FILE_EVENT kèm manifest text, không gửi payload file thật.
9. Server xử lý applyFileChange(), saveVersionChunks() và broadcast.

DELETE vẫn giữ luồng FILE_EVENT hiện tại.

---

## 9. Luồng Rollback

Khi User yêu cầu rollback về version V:

1. Server đọc file_version_chunks theo file_id và version V.
2. Sắp xếp theo chunk_order ASC.
3. Đọc nội dung từng chunk từ bảng chunks.
4. Ghép chunk theo đúng thứ tự.
5. Tạo file hoàn chỉnh.
6. Broadcast FILE_DELIVER tới các Client trong workspace.

Cần kiểm tra kỹ việc cập nhật metadata, version mới, checksum và trạng thái đồng bộ sau rollback.

---

## 10. Luồng Local Backup

Trước khi overwrite file local:

1. Chunk file hiện tại.
2. Tạo manifest.
3. Lưu file .manifest vào thư mục BackUp/.
4. Nếu là version gần nhất, lưu thêm full copy.

Ví dụ tên manifest:

`report_v00000000000000000003_20261106_084532_123.manifest`

Restore offline khi Server down:
- Đọc full copy của version gần nhất.

Restore online khi Server hoạt động:
- Đọc manifest.
- Gửi yêu cầu lấy chunk từ Server.
- Ghép chunk để khôi phục file.

---

## 11. Các file dự kiến tạo mới

### RabinChunker.java

Đường dẫn:

`src/main/java/vn/edu/pbl4sync/common/RabinChunker.java`

Yêu cầu:
- CDC engine dùng Rabin fingerprint.
- POLY = 0x3DA3358B4DC173L.
- WINDOW = 64.
- MIN_SIZE = 1MB.
- MAX_SIZE = 16MB.
- MASK_TARGET = (1L << 22) - 1.
- Pre-compute OUT_TABLE và MOD_TABLE.
- API:
  - List<Chunk> chunk(Path file)
  - byte[] readChunk(Path file, long offset, int length)
  - Record Chunk(long offset, int length, String hash)

### ChunkManifest.java

Đường dẫn:

`src/main/java/vn/edu/pbl4sync/common/ChunkManifest.java`

Format:

PBL4SYNC_MANIFEST_V1
totalSize=<long>
chunkCount=<int>
<hash> <length>
...

API:
- static ChunkManifest from(List<Chunk> chunks, long totalSize)
- static ChunkManifest parse(String text)
- String serialize()
- void writeTo(Path path)
- static ChunkManifest readFrom(Path path)
- List<String> allHashes()
- void assembleTo(Path destination, ChunkReader reader)
- Interface ChunkReader: byte[] read(String hash)

### ChunkStore.java

Đường dẫn:

`src/main/java/vn/edu/pbl4sync/server/db/ChunkStore.java`

API:
- Set<String> missingHashes(List<String> hashes)
- void saveChunk(String hash, int size, byte[] content)
- void saveVersionChunks(long fileId, long version, ChunkManifest manifest)
- byte[] loadChunk(String hash)
- ChunkManifest loadManifest(long fileId, long version, long totalSize)
- int purgeOrphanChunks()

Dùng chung SimpleConnectionPool với Database.

---

## 12. Các file dự kiến sửa

### MessageTypes.java
Thêm message type mới.

### Database.java
- Thêm 3 bảng mới vào initializeSchema().
- Thêm getter pool() nếu thực sự cần.
- Thêm copyFileVersion().
- Thêm listFileVersions().
- Thêm markReplica().
- Giữ nguyên applyFileChange() hiện tại.

### ServerCore.java
- Thêm field ChunkStore.
- Khởi tạo ChunkStore sau khi tạo Database.
- Thêm xử lý CHUNK_QUERY.
- Thêm xử lý CHUNK_UPLOAD.
- Thêm xử lý FILE_VERSIONS_REQUEST.
- Thêm xử lý FILE_ROLLBACK_REQUEST.
- Sửa processFileEvent() để lưu manifest sau khi applyFileChange() thành công.
- Thêm xử lý rollback dùng ChunkStore và ChunkManifest.
- Tuyệt đối giữ nguyên phần accept socket.

### AgentService.java
Sửa uploadLocalChange():

- DELETE: giữ nguyên FILE_EVENT hiện tại.
- CREATE/MODIFY:
  1. RabinChunker.chunk(file).
  2. ChunkManifest.from(chunks, size).
  3. Gửi CHUNK_QUERY.
  4. Nhận CHUNK_QUERY_RESULT.
  5. Upload chunk còn thiếu.
  6. Gửi FILE_EVENT kèm manifest, không có payload file thật.

### LocalBackupStore.java
Tạo mới trong client/sync/.

API dự kiến:
- archiveVersion()
- backupIfDifferent()
- listVersions()
- readManifest()

Lưu manifest cho mọi version và full copy cho version gần nhất.

### LocalStateStore.java
Chỉ thêm clearConflicted(long ws, String path) nếu thực sự cần thiết.

---

## 13. Yêu cầu kỹ thuật bắt buộc

- Java 17.
- Không dùng thư viện ngoài.
- Thread-safe với nhiều Agent kết nối đồng thời.
- Xác minh SHA-256 trước khi lưu chunk.
- Batch insert cho file_version_chunks.
- Fallback: version cũ chưa có chunk thì dùng copyFileVersion().
- INSERT IGNORE cho chunks.
- Có purgeOrphanChunks().
- Xử lý transaction và lỗi disconnect hợp lý.
- Không để file version được ghi nhận thành công khi chunk chưa upload đầy đủ.
- Không làm hỏng các luồng đồng bộ file hiện tại ngoài phạm vi Backup/Rollback.

---

## 14. Thứ tự triển khai dự kiến

1. RabinChunker.java
2. ChunkManifest.java
3. Database.java – schema và pool getter
4. ChunkStore.java
5. MessageTypes.java
6. ServerCore.java
7. AgentService.java
8. LocalBackupStore.java
9. LocalStateStore.java

Thứ tự trên là dự kiến. Nếu kiến trúc thực tế yêu cầu thay đổi, hãy giải thích trước và chờ tôi đồng ý.

---

# 15. QUY TẮC BẢO VỆ CODE HIỆN CÓ

Tôi cho phép bạn triển khai và sửa đổi code khi cần thiết, nhưng không được tự ý thay đổi code do các thành viên khác đã viết.

Trước khi sửa bất kỳ file hiện có nào:

1. Giải thích tại sao bắt buộc phải sửa file đó.
2. Chỉ rõ class, method và đoạn logic dự kiến thay đổi.
3. Giải thích code hiện tại đang hoạt động như thế nào.
4. Nêu rõ phần nào được giữ nguyên, phần nào sẽ thay đổi và ảnh hưởng đến các chức năng khác.
5. Đưa ra phương án ít can thiệp nhất.

Sau đó DỪNG LẠI và chờ tôi xác nhận. Chỉ được sửa file sau khi tôi đồng ý rõ ràng.

Đối với file mới, bạn có thể triển khai theo phạm vi đã thống nhất, nhưng không được tạo file trước khi hoàn tất kiểm tra kiến trúc và xác nhận kế hoạch triển khai.

Không tự ý refactor, đổi tên, xóa method, thay đổi cấu trúc hoặc tối ưu những phần không liên quan đến chức năng Backup/Rollback.

Không được xem yêu cầu triển khai toàn bộ dự án là sự cho phép sửa tất cả file hiện có.

---

# 16. CÁCH LÀM VIỆC

1. Đọc và hiểu source code thực tế trong nhánh feature/chunk-backup-rollback. Dùng nhánh basic-features để tham chiếu khi cần.
2. Kiểm tra các luồng hiện tại liên quan đến upload, download, backup, rollback, database và protocol.
3. Đối chiếu đặc tả với kiến trúc thực tế.
4. Không sửa code ngay khi mới bắt đầu.
5. Lập kế hoạch triển khai cụ thể.
6. Liệt kê chính xác file mới, file cần sửa, method cần thay đổi.
7. Nêu các rủi ro, phụ thuộc và phương án kiểm thử.
8. Báo cáo cho tôi và DỪNG LẠI để tôi duyệt kế hoạch.
9. Sau khi được duyệt, triển khai từng bước.
10. Hoàn thành mỗi bước phải báo cáo kết quả và chờ tôi xác nhận trước khi sang bước tiếp theo.

Nếu phát hiện đặc tả không phù hợp với kiến trúc thực tế, hãy báo cáo và đề xuất phương án trước. Không tự ý thay đổi thiết kế.

**YÊU CẦU ĐẦU TIÊN:**

Bây giờ hãy bắt đầu bằng việc kiểm tra Git branch và working tree, sau đó đọc source code để hiểu kiến trúc hiện tại.

Ở giai đoạn này chỉ được đọc và phân tích. Không tạo file, không sửa file, không chạy migration, không thay đổi database và không thực hiện thao tác Git làm thay đổi trạng thái dự án.

Cuối cùng, báo cáo kế hoạch triển khai hoàn chỉnh và chờ tôi xác nhận.