# FlowDriver-Android 🌊📱

> **A fork of [FlowDriver](https://github.com/NullLatency/FlowDriver) by [NullLatency](https://github.com/NullLatency) — with an optimized server and native Android client.**

> **یک fork از [FlowDriver](https://github.com/NullLatency/FlowDriver) ساخته [NullLatency](https://github.com/NullLatency) — با سرور بهینه‌شده و کلاینت اندروید.**

---

<div align="center">

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Linux%20%7C%20Windows-blue)
![Language](https://img.shields.io/badge/language-Go%20%7C%20Android-00ADD8)
![Fork](https://img.shields.io/badge/fork%20of-FlowDriver-orange)
![License](https://img.shields.io/badge/license-see%20original-lightgrey)

</div>

---

## What's Different in This Fork? / تغییرات این Fork چیه؟

### 🇬🇧 English

This fork builds on the original FlowDriver project with two major additions:

- **Optimized Server**: The server-side code has been improved for better performance and stability.
- **Android Client (APK)**: A native Android application that lets you use FlowDriver directly on your phone — no PC required for the client side.

### 🇮🇷 فارسی

این fork بر پایه پروژه اصلی FlowDriver ساخته شده و دو تغییر اصلی داره:

- **سرور بهینه‌شده**: کد سمت سرور برای عملکرد و پایداری بهتر بهینه‌سازی شده.
- **کلاینت اندروید (APK)**: یک اپلیکیشن اندروید که بهت اجازه میده FlowDriver رو مستقیماً روی گوشیت استفاده کنی — بدون نیاز به PC برای کلاینت.

---

## Download / دانلود

📦 **[Download latest APK →](../../releases/latest)**

---

## How it Works / نحوه عملکرد

### 🇬🇧 English

FlowDriver tunnels SOCKS5 traffic through Google Drive API requests, allowing reliable communication in restrictive networks (e.g. heavy DPI/filtering environments).

1. **Android Client**: Captures SOCKS5 requests on your phone and uploads them as binary packets to a shared Google Drive folder.
2. **Server**: Polls the Drive folder, processes requests, opens real TCP connections, and returns responses.

### 🇮🇷 فارسی

FlowDriver ترافیک SOCKS5 رو از طریق درخواست‌های Google Drive API تونل می‌کنه و ارتباط مطمئن در شبکه‌های محدود (مثل محیط‌های با DPI سنگین) رو ممکن میکنه.

1. **کلاینت اندروید**: درخواست‌های SOCKS5 رو روی گوشیت دریافت کرده و به صورت بسته‌های باینری در یک پوشه مشترک گوگل درایو آپلود میکنه.
2. **سرور**: پوشه درایو رو بررسی میکنه، درخواست‌ها رو پردازش میکنه، اتصال TCP واقعی برقرار میکنه و پاسخ‌ها رو برمیگردونه.

---

## Setup / راه‌اندازی

### Prerequisites / پیش‌نیازها

- **Go** 1.21+
- **Android** 8.0+ (for APK)
- **Google Drive API credentials** (`credentials.json`)

---

### Server Setup / راه‌اندازی سرور

#### 🇬🇧 English

1. Build the optimized server binary:
   ```bash
   go build -o bin/server ./cmd/server
   ```
2. Create your `server_config.json`:
   ```json
   {
     "storage_type": "google",
     "google_folder_id": "YOUR_FOLDER_ID",
     "refresh_rate_ms": 100,
     "flush_rate_ms": 300
   }
   ```
3. Run the server:
   ```bash
   ./bin/server -c server_config.json -gc credentials.json
   ```

#### 🇮🇷 فارسی

1. فایل اجرایی سرور رو بساز:
   ```bash
   go build -o bin/server ./cmd/server
   ```
2. فایل `server_config.json` رو بساز:
   ```json
   {
     "storage_type": "google",
     "google_folder_id": "YOUR_FOLDER_ID",
     "refresh_rate_ms": 100,
     "flush_rate_ms": 100
   }
   ```
3. سرور رو اجرا کن:
   ```bash
   ./bin/server -c server_config.json -gc credentials.json
   ```

---

### Android Client Setup / راه‌اندازی کلاینت اندروید

#### 🇬🇧 English

1. Download the APK from the [Releases](../../releases/latest) page and install it.
2. On first launch, you will be prompted to authenticate with your Google account.
3. Enter your `google_folder_id` — this **must** match the folder ID used on the server.
4. Set your desired `listen_addr` (default: `127.0.0.1:1080`).
5. Tap **Start** — the app will run as a background service and act as a local SOCKS5 proxy.
6. Point your browser or any SOCKS5-compatible app to `127.0.0.1:1080`.

#### 🇮🇷 فارسی

1. APK رو از صفحه [Releases](../../releases/latest) دانلود و نصب کن.
2. در اولین راه‌اندازی، ازت خواسته میشه با اکانت گوگلت لاگین کنی.
3. `google_folder_id` رو وارد کن — این باید **دقیقاً** همون folder ID باشه که روی سرور استفاده کردی.
4. `listen_addr` دلخواهت رو تنظیم کن (پیشفرض: `127.0.0.1:1080`).
5. روی **Start** بزن — اپ به عنوان یه سرویس پس‌زمینه اجرا میشه و به عنوان پروکسی SOCKS5 محلی عمل میکنه.
6. مرورگر یا هر اپی که SOCKS5 رو پشتیبانی میکنه رو روی `127.0.0.1:1080` تنظیم کن.

---

## ⚠️ Disclaimer / سلب مسئولیت

**🇬🇧** This project is intended for personal use and research purposes only. Do not use it for illegal purposes or in production environments. The authors are not responsible for any misuse.

**🇮🇷** این پروژه صرفاً برای استفاده شخصی و اهداف تحقیقاتی در نظر گرفته شده. لطفاً از آن برای مقاصد غیرقانونی استفاده نکنید. نویسندگان هیچ مسئولیتی در قبال سوء استفاده ندارند.

---

## Credits / اعتبارات

This project is a fork of **[FlowDriver](https://github.com/NullLatency/FlowDriver)** originally created by **[NullLatency](https://github.com/NullLatency)**. All core tunnel logic and architecture belongs to them. This fork adds Android support and server optimizations.

این پروژه یک fork از **[FlowDriver](https://github.com/NullLatency/FlowDriver)** ساخته **[NullLatency](https://github.com/NullLatency)** هست. تمام منطق اصلی تونل و معماری متعلق به ایشان است. این fork پشتیبانی از اندروید و بهینه‌سازی سرور رو اضافه کرده.

---

## Star History / تاریخچه ستاره‌ها

If you find this useful, please ⭐ both this repo and the [original FlowDriver](https://github.com/NullLatency/FlowDriver)!

اگه مفید بود، لطفاً هم به این ریپو و هم به [FlowDriver اصلی](https://github.com/NullLatency/FlowDriver) ستاره بدید! ⭐
