# =====================================================
# MyDrive-Sync - Script khởi động nhanh
# Chạy script này mỗi lần muốn vào dự án
# =====================================================

# Đổi lại đường dẫn nếu dự án của bạn ở chỗ khác
Set-Location "D:\MyDrive-Sync"

# --- Cấu hình TLS Server ---
$env:PBL4SYNC_TLS_KEYSTORE          = "$PWD\certs\server.p12"
$env:PBL4SYNC_TLS_KEYSTORE_PASSWORD = "21102026"
$env:PBL4SYNC_TLS_KEY_PASSWORD      = "21102026"

# --- Cấu hình TLS Client ---
$env:PBL4SYNC_TLS_TRUSTSTORE          = "$PWD\certs\server-truststore.p12"
$env:PBL4SYNC_TLS_TRUSTSTORE_PASSWORD = "19102026"

Write-Host ""
Write-Host "✅ Đã cấu hình TLS xong!" -ForegroundColor Green
Write-Host "   Keystore  : $env:PBL4SYNC_TLS_KEYSTORE"
Write-Host "   Truststore: $env:PBL4SYNC_TLS_TRUSTSTORE"
Write-Host ""
Write-Host "Đang mở VS Code..." -ForegroundColor Cyan

code .
