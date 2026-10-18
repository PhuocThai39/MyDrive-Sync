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

db.url=jdbc:mysql://localhost:3306/pbl4sync?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh
db.user=root
db.password=MAT_KHAU_MYSQL_CUA_MAY_SERVER

Ví dụ MySQL root password là 123456:

db.user=root
db.password=123456

Lưu ý: db.password là mật khẩu MySQL, không phải mật khẩu tài khoản Admin của ứng dụng.

Project sẽ tự tạo database pbl4sync và các bảng cần thiết khi Server chạy lần đầu.

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

7. Chạy Agent

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

8. Kết nối nhiều máy trong cùng LAN

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

9. Vai trò của từng chương trình

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

10. Workflow Git đề xuất cho nhóm

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

11. Trước khi push code

Luôn chạy:

git status

Không push các thông tin nhạy cảm như mật khẩu MySQL thật.

Khuyến nghị file config/server.properties không chứa password thật khi push lên GitHub.

Ví dụ trên GitHub chỉ để:

db.user=root
db.password=YOUR_MYSQL_PASSWORD

Mỗi thành viên tự sửa password trên máy mình.

12. Cách cập nhật khi thành viên khác đã merge code mới

git checkout main
git pull origin main

Nếu đang làm branch riêng:

git checkout ten-branch-cua-ban
git merge main

Nếu có conflict thì xử lý conflict rồi:

git add .
git commit -m "Resolve merge conflict"

13. Kịch bản test nhóm

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

14. Lỗi thường gặp

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
