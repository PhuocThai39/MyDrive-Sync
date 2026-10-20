PBL4 Company Sync - Hướng dẫn lấy project từ GitHub và chạy

Tài liệu này dành cho các thành viên trong nhóm sau khi project đã được đẩy lên GitHub.

1. Công nghệ sử dụng

Java 17 trở lên

Java Swing

MySQL

JDBC

TCP Socket

WatchService

VS Code

2. Phần mềm cần cài trước

Mỗi thành viên cần cài:

Git

JDK 17+

VS Code

Extension VS Code: Extension Pack for Java

MySQL Server nếu máy đó chạy Server hoặc muốn test Server local

Kiểm tra Java:

java -version
javac -version

Kiểm tra Git:

git --version

3. Clone project từ GitHub

Mở Terminal tại thư mục muốn lưu project:

git clone <LINK_GITHUB_CUA_NHOM>

Ví dụ:

git clone https://github.com/username/PBL4Sync.git

Sau đó:

cd PBL4Sync

Mở bằng VS Code:

code .

Hoặc vào VS Code -> File -> Open Folder và chọn thư mục PBL4Sync.

4. Lấy bản code mới nhất

Trước khi bắt đầu làm việc mỗi ngày:

git checkout main
git pull origin main

Nếu nhóm dùng branch riêng cho từng thành viên:

git checkout ten-branch-cua-ban
git pull origin main

Sau đó merge/rebase theo quy ước của nhóm.

5. Cấu hình MySQL cho máy chạy Server

Mở file:

config/server.properties

Cấu hình ví dụ:

db.url=jdbc:mysql://localhost:3306/pbl4sync?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh
db.user=root
db.sslMode=DISABLED
db.password=MAT_KHAU_MYSQL_CUA_MAY_SERVER

Ví dụ MySQL root password là 123456:

db.user=root
db.password=123456

Lưu ý: db.password là mật khẩu MySQL, không phải mật khẩu tài khoản Admin của ứng dụng.

Project sẽ tự tạo database pbl4sync và các bảng cần thiết khi Server chạy lần đầu.

`db.sslMode=DISABLED` giữ tương thích cho MySQL chạy local trên cùng máy. Nếu
MySQL ở máy khác, hãy cấu hình TLS trên MySQL Server và dùng
`db.sslMode=VERIFY_IDENTITY` để mã hóa kết nối đồng thời kiểm tra CA và hostname
của máy DB. Địa chỉ host trong `db.url` phải khớp DNS/IP trong SAN của
certificate MySQL. Không dùng `REQUIRED` thay cho `VERIFY_IDENTITY` khi cần
chống giả mạo server.

Với `VERIFY_IDENTITY`, lấy CA certificate đáng tin cậy của MySQL Server và
chuyển tới máy chạy ứng dụng qua kênh an toàn. Tạo truststore PKCS#12:

```powershell
New-Item -ItemType Directory -Force certs
$env:PBL4SYNC_DB_TRUSTSTORE_PASSWORD = Read-Host "MySQL truststore password"
keytool -importcert -noprompt -alias mysql-ca -file C:\path\to\mysql-ca.pem `
  -storetype PKCS12 -keystore certs\mysql-truststore.p12 `
  -storepass:env PBL4SYNC_DB_TRUSTSTORE_PASSWORD
```

Thiết lập `PBL4SYNC_DB_TRUSTSTORE_PASSWORD` trong môi trường khởi chạy Server.
Truststore mặc định là `certs/mysql-truststore.p12`; có thể đổi vị trí bằng
`PBL4SYNC_DB_TRUSTSTORE`. Mật khẩu không được ghi trong `server.properties`.
Nếu MySQL dùng chứng chỉ tự ký, certificate đó có thể làm CA tin cậy trong
truststore, nhưng vẫn phải xác minh fingerprint độc lập. MySQL Server cũng
phải được cấu hình bật TLS; ứng dụng sẽ báo lỗi nếu chế độ xác minh yêu cầu
truststore mà truststore/mật khẩu chưa được cấu hình.

6. Chạy Server

Chỉ cần một máy làm Server khi cả nhóm test chung.

Trong VS Code:

Mở Run and Debug.

Chọn Run PBL4 Server.

Khi cửa sổ Server Monitor xuất hiện, bấm Start Server.

Port mặc định:

5000

Nếu Server và Agent chạy trên cùng máy, Agent kết nối:

IP: 127.0.0.1
Port: 5000

7. Bật TLS cho kết nối Agent-Server

Server và Agent bắt buộc dùng TLS 1.2 trở lên. Cần tạo một PKCS#12 chứa
private key/certificate cho Server và một truststore chứa certificate công khai
đó cho các Agent. Client xác minh certificate và tên máy trong SAN; không có
fallback về TCP không mã hóa.

Trên máy Server, mở PowerShell tại thư mục project và đặt mật khẩu store vào
environment (không ghi mật khẩu vào file được Git theo dõi):

```powershell
$env:PBL4SYNC_TLS_KEYSTORE_PASSWORD = Read-Host "TLS keystore password"
$env:PBL4SYNC_TLS_KEY_PASSWORD = $env:PBL4SYNC_TLS_KEYSTORE_PASSWORD
New-Item -ItemType Directory -Force certs
keytool -genkeypair -alias pbl4sync-server -keyalg RSA -keysize 3072 `
  -storetype PKCS12 -keystore certs\server.p12 -validity 365 `
  -dname "CN=localhost" `
  -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.10" `
  -storepass:env PBL4SYNC_TLS_KEYSTORE_PASSWORD `
  -keypass:env PBL4SYNC_TLS_KEY_PASSWORD
keytool -exportcert -rfc -alias pbl4sync-server -keystore certs\server.p12 `
  -storetype PKCS12 -file certs\server.crt `
  -storepass:env PBL4SYNC_TLS_KEYSTORE_PASSWORD
```

Thay `192.168.1.10` bằng địa chỉ IP hoặc DNS mà Agent sẽ dùng để kết nối.
Thêm tất cả tên/IP cần thiết vào SAN trước khi cấp certificate. Nếu certificate
đã tạo không có SAN phù hợp, hãy tạo lại certificate.

Trên mỗi máy Agent, chuyển `server.crt` qua kênh đáng tin cậy, xác minh
fingerprint của certificate với quản trị viên qua một kênh độc lập, rồi nhập
certificate vào truststore:

```powershell
$env:PBL4SYNC_TLS_TRUSTSTORE_PASSWORD = Read-Host "TLS truststore password"
New-Item -ItemType Directory -Force certs
keytool -importcert -noprompt -alias pbl4sync-server -file certs\server.crt `
  -storetype PKCS12 -keystore certs\server-truststore.p12 `
  -storepass:env PBL4SYNC_TLS_TRUSTSTORE_PASSWORD
```

Giữ `server.p12` và mật khẩu keystore chỉ trên máy Server. Chỉ phân phối
certificate công khai/truststore cho Agent. Thư mục `certs` bị loại khỏi Git.
Các biến environment cần được đặt trong môi trường đã khởi chạy ứng dụng:

- Server: `PBL4SYNC_TLS_KEYSTORE` (mặc định `certs/server.p12`),
  `PBL4SYNC_TLS_KEYSTORE_PASSWORD`; `PBL4SYNC_TLS_KEY_PASSWORD` mặc định dùng
  cùng mật khẩu keystore.
- Agent: `PBL4SYNC_TLS_TRUSTSTORE` (mặc định `certs/server-truststore.p12`),
  `PBL4SYNC_TLS_TRUSTSTORE_PASSWORD`.

Nếu chạy từ VS Code, hãy khởi chạy VS Code từ PowerShell đã đặt biến hoặc cấu
hình các biến trong môi trường người dùng rồi khởi động lại VS Code. Mọi Agent
phải kết nối bằng đúng DNS/IP có trong SAN của certificate. Thiếu store, sai
mật khẩu hoặc certificate/hostname không khớp sẽ làm kết nối thất bại.
TLS Agent-Server và TLS JDBC là hai kênh riêng, cần cấu hình certificate và
truststore riêng cho từng dịch vụ.

8. Chạy Agent

Trong VS Code:

Mở Run and Debug.

Chọn Run PBL4 Agent.

Nhập IP Server và port 5000.

Đăng nhập bằng tài khoản được cấp.

Tài khoản Admin mặc định ở lần chạy đầu:

Username: admin
Password: admin123

Có thể thay đổi password mặc định trong:

config/server.properties

9. Kết nối nhiều máy trong cùng LAN

Giả sử máy chạy Server có IPv4:

192.168.1.10

Máy Server chạy Java Server ở port:

5000

Các máy thành viên chạy Agent và nhập:

Server IP: 192.168.1.10
Port: 5000

Để xem IPv4 máy Server trên Windows:

ipconfig

Tìm dòng:

IPv4 Address

Nếu Agent không kết nối được, kiểm tra:

Server đã bấm Start Server chưa.

Hai máy có cùng mạng LAN/Wi-Fi không.

Windows Firewall có chặn Java hoặc TCP port 5000 không.

IP Server có đúng không.

10. Vai trò của từng chương trình

Agent A ---------\
Agent B ----------> Java Server ----> MySQL
Agent C ---------/

Server

Quản lý:

Đăng nhập

User

Workspace

Leader / Member

Permission

Agent online/offline

File metadata

Version

Điều phối đồng bộ file

Activity Log

Agent

Quản lý:

Giao diện Swing

Shared Folder local

Upload / Save a Copy

WatchService

Auto Sync

Nhận/gửi file

Workspace của người dùng

MySQL

Lưu:

User

Workspace

Member

Permission

Metadata file

Version

Agent

Activity Log

File thật được lưu tại các Agent; Server chủ yếu quản lý metadata và điều phối đồng bộ.

11. Workflow Git đề xuất cho nhóm

Không nên code trực tiếp toàn bộ trên main.

Mỗi thành viên tạo branch riêng:

git checkout main
git pull origin main
git checkout -b ten-thanh-vien

Ví dụ:

git checkout -b thai

Sau khi sửa code:

git status
git add .
git commit -m "Mo ta thay doi"
git push origin thai

Sau đó tạo Pull Request trên GitHub để merge vào main.

12. Trước khi push code

Luôn chạy:

git status

Không push các thông tin nhạy cảm như mật khẩu MySQL thật.

Khuyến nghị file config/server.properties không chứa password thật khi push lên GitHub.

Ví dụ trên GitHub chỉ để:

db.user=root
db.password=YOUR_MYSQL_PASSWORD

Mỗi thành viên tự sửa password trên máy mình.

13. Cách cập nhật khi thành viên khác đã merge code mới

git checkout main
git pull origin main

Nếu đang làm branch riêng:

git checkout ten-branch-cua-ban
git merge main

Nếu có conflict thì xử lý conflict rồi:

git add .
git commit -m "Resolve merge conflict"

14. Kịch bản test nhóm

Có thể dùng 3 máy:

PC 1: Server + MySQL + Agent Admin
PC 2: Agent User A
PC 3: Agent User B

Trình tự:

PC 1 chạy MySQL.

PC 1 chạy PBL4 Server.

PC 1 bấm Start Server.

PC 2 và PC 3 chạy Agent.

PC 2/3 nhập IP LAN của PC 1.

Admin tạo User và Workspace.

Gán Leader / Member / Permission.

User đăng nhập.

Tạo hoặc sửa file trong Shared Folder.

Kiểm tra file được tự động đồng bộ sang Agent khác.

15. Lỗi thường gặp

MySQL báo Access denied for user 'root'@'localhost'

Kiểm tra:

db.user=root
db.password=...

Password phải đúng với MySQL của máy Server.

Agent không kết nối được Server

Kiểm tra:

Server IP
Port 5000
Firewall
Server đã Start

Chạy cùng máy

Dùng:

127.0.0.1:5000

Chạy khác máy cùng LAN

Dùng IPv4 của máy Server, ví dụ:

192.168.1.10:5000

Tóm tắt nhanh

# Clone

git clone <LINK_GITHUB>
cd PBL4Sync
code .

# Lấy code mới

git pull origin main

Sau đó:

1. Máy Server cấu hình MySQL
2. Run PBL4 Server
3. Bấm Start Server
4. Run PBL4 Agent
5. Agent nhập IP Server : 5000
6. Login và test
