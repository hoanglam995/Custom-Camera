# Custom Camera v3.0 (Non-Repeating Random & Web Image Normalizer)

Ứng dụng thay thế Camera Intent (`android.media.action.IMAGE_CAPTURE`), hỗ trợ:
- Chọn nhiều ảnh và lấy ngẫu nhiên không lặp lại (Shuffle Bag: dùng hết lượt mới quay vòng, chống trùng ảnh giữa cuối vòng cũ và đầu vòng mới).
- Tự động chuẩn hóa ảnh tải từ trên mạng (WebP, PNG, JPEG từ Chrome, Zalo, Facebook...) sang chuẩn JPEG có hỗ trợ ghi EXIF timestamp.

## Cách 1: Build nhanh bằng Docker
```bash
chmod +x build_apk_docker.sh
./build_apk_docker.sh
```
File APK xuất ra tại: `app/build/outputs/apk/debug/app-debug.apk`

## Cách 2: Build bằng GitHub Actions
Đẩy toàn bộ mã nguồn lên GitHub, vào tab **Actions** để tải file `app-debug.apk` trong mục Artifacts.
