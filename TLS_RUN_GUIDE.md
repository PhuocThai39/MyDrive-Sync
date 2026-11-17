# Hướng dẫn chạy Server và Client từ đầu với TLS

## Lần đầu tạo certs

## 1. Tạo certificate cho TLS Server

Server và Client phải dùng TLS 1.2 hoặc mới hơn. Dưới đây là cách sinh certificate cho Server trên Windows PowerShell.

### 1.1 Tạo thư mục certs

```powershell
New-Item -ItemType Directory -Force certs
```

### 1.2 Thiết lập thư mục làm việc

```powershell
$env:PBL4SYNC_TLS_KEYSTORE_PASSWORD = Read-Host "Nhập mật khẩu keystore Server"
$env:PBL4SYNC_TLS_KEY_PASSWORD = $env:PBL4SYNC_TLS_KEYSTORE_PASSWORD
```

### 1.3 Tạo keystore cho Server

Thay IP `192.168.1.10` bằng IP thật của máy Server trong mạng LAN.

```powershell
keytool -genkeypair -alias pbl4sync-server -keyalg RSA -keysize 3072 `
  -storetype PKCS12 -keystore certs\server.p12 -validity 365 `
  -dname "CN=localhost" `
  -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.1.10" `
  -storepass:env PBL4SYNC_TLS_KEYSTORE_PASSWORD `
  -keypass:env PBL4SYNC_TLS_KEY_PASSWORD
```

### 1.4 Xuất certificate công khai

```powershell
keytool -exportcert -rfc -alias pbl4sync-server -keystore certs\server.p12 `
  -storetype PKCS12 -file certs\server.crt `
  -storepass:env PBL4SYNC_TLS_KEYSTORE_PASSWORD
```

Lưu ý quan trọng:

- `server.p12` là keystore của máy Server, giữ bí mật
- `server.crt` là certificate công khai, có thể chia sẻ cho Client
- SAN phải chứa đúng IP/DNS của máy Server mà Client sẽ dùng để kết nối
- Nếu Client kết nối bằng `127.0.0.1`, phải có `ip:127.0.0.1`
- Nếu Client kết nối bằng IP LAN, phải có đúng IP đó trong SAN

## 2. Tạo truststore cho Client

Gửi file `server.crt` từ server sang client bằng đường tin cậy

Trong PowerShell trên máy Client, chạy:

```powershell
$env:PBL4SYNC_TLS_TRUSTSTORE_PASSWORD = Read-Host "Nhập mật khẩu truststore Client"
New-Item -ItemType Directory -Force certs
```

Sau đó import file `server.crt` đã nhận từ Server vào truststore:

```powershell
keytool -importcert -noprompt -alias pbl4sync-server -file certs\server.crt `
  -storetype PKCS12 -keystore certs\server-truststore.p12 `
  -storepass:env PBL4SYNC_TLS_TRUSTSTORE_PASSWORD
```

Nếu cùng một máy chạy cả Server và Client, vẫn nên tạo cả 2 file:

- `certs/server.p12` cho Server
- `certs/server-truststore.p12` cho Client

## 3. Những lần chạy sau

### 3.1 Trên máy Server

Mở PowerShell tại thư mục gốc project trước khi chạy Server. Nhập mật khẩu đã
dùng để tạo keystore; lệnh kiểm tra bên dưới truyền mật khẩu cho `keytool`
qua biến môi trường, không ghi mật khẩu trực tiếp vào file Markdown:

Tự sửa location

```powershell
Set-Location "D:\Nam3\Ki1\PBL4\MyDrive-Sync"
$env:PBL4SYNC_TLS_KEYSTORE = "$PWD\certs\server.p12"
$env:PBL4SYNC_TLS_KEYSTORE_PASSWORD = Read-Host "Nhập mật khẩu keystore Server"
$env:PBL4SYNC_TLS_KEY_PASSWORD = $env:PBL4SYNC_TLS_KEYSTORE_PASSWORD
keytool -list -keystore $env:PBL4SYNC_TLS_KEYSTORE -storetype PKCS12 `
  -storepass:env PBL4SYNC_TLS_KEYSTORE_PASSWORD
  code .
```

### 3.2 Trên máy Client

Mở PowerShell tại thư mục gốc project trước khi chạy Client. Nhập mật khẩu
truststore đã dùng lúc tạo file:

```powershell
Set-Location "D:\Nam3\Ki1\PBL4\MyDrive-Sync"
$env:PBL4SYNC_TLS_TRUSTSTORE = "$PWD\certs\server-truststore.p12"
$env:PBL4SYNC_TLS_TRUSTSTORE_PASSWORD = Read-Host "Nhập mật khẩu truststore Client"
keytool -list -keystore $env:PBL4SYNC_TLS_TRUSTSTORE -storetype PKCS12 `
  -storepass:env PBL4SYNC_TLS_TRUSTSTORE_PASSWORD
  code .
```

Lưu ý:

- Biến môi trường phải được set trước khi khởi động VS Code hoặc Java process
- Không ghi mật khẩu thật vào file Markdown hoặc file được Git theo dõi

## 3.3 Nếu chạy Server và client trên 1 máy

```powershell
Set-Location "D:\Nam3\Ki1\PBL4\MyDrive-Sync"

$env:PBL4SYNC_TLS_KEYSTORE = "$PWD\certs\server-lan-172.p12"
$env:PBL4SYNC_TLS_KEYSTORE_PASSWORD = Read-Host "Mat khau keystore Server"
$env:PBL4SYNC_TLS_KEY_PASSWORD = $env:PBL4SYNC_TLS_KEYSTORE_PASSWORD

$env:PBL4SYNC_TLS_TRUSTSTORE = "$PWD\certs\server-lan-172-truststore.p12"
$env:PBL4SYNC_TLS_TRUSTSTORE_PASSWORD = Read-Host "Mat khau truststore Client"

code .
```
