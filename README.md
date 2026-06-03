# MHXX スナイプ APK (Bluetooth HID 統合版)

MHXX護石スナイプツール + **JoyConDroid方式 Bluetooth HID コントローラー** + **マクロ自動操作**の統合版。

## 主な機能

| 機能 | 詳細 |
|---|---|
| OCRエンジン | Google ML Kit（日本語・完全オフライン） |
| 護石スナイプ | フレーム計算・スキル検索・乱数生成 |
| Bluetooth接続 | Bluetooth HID（JoyConDroid方式）SwitchのMACアドレスで接続 |
| コントローラー操作 | 全ボタン・スティック操作をHTMLからワンタッチ |
| マクロ機能 | カスタムマクロ記録・再生・ループ |
| プリセットマクロ | 護石スナイプ・セーブ・フレームジャンプ等 |

## 動作環境

- **Android 9.0 (API 28) 以上**（BluetoothHidDevice API必須）
- Bluetooth HIDプロファイルをサポートする端末

## ビルド方法

### GitHub Actions（推奨）
1. このプロジェクトをGitHubにpush
2. `Actions` → `Build APK` → `Run workflow`
3. Artifactsから `mhxx-snipe-bt-debug` をダウンロード

### Android Studio
```bash
gradle wrapper --gradle-version 8.4  # 初回のみ
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Bluetooth接続手順

1. Switchの「設定 → 本体 → MACアドレス（無線LAN）」を確認
2. アプリの「📡 BT接続」タブを開く
3. SwitchのMACアドレスを入力して「接続」をタップ
4. Switch側で「コントローラー → 持ちかた/順番を変える」を開く
5. スマホのBluetoothをONにする（接続まで10〜30秒）
6. 接続後、「🎮 コントローラー」タブで操作可能

## 接続の仕組み

```
Android App（本アプリ）
  │
  ├── HTML/WebView（スナイプUI）
  │     └── window.AndroidBridge.pressButton() 等
  │
  ├── SwitchBridge（@JavascriptInterface）
  │     └── JoyController.setButton()
  │
  └── BluetoothControllerService（JoyConDroid）
        └── BluetoothHidDevice API → Switch
```

## アーキテクチャ

```
com.mhxx.snipe/
├── MainActivity.kt          ← メイン（WebView + ML Kit + SwitchBridge）
└── (assets)
    └── snipe_modified.html  ← スナイプUI + コントローラーUI

com.rdapps.gamepad/          ← JoyConDroid BT HIDエンジン（そのまま移植）
├── service/
│   └── BluetoothControllerService.java  ← BTフォアグラウンドサービス
├── protocol/
│   └── JoyController.java  ← Switchプロトコル実装
└── ...
```

## 依存関係

- `com.google.mlkit:text-recognition-japanese:16.0.0`
- `org.projectlombok:lombok:1.18.30`
- `com.squareup.retrofit2:retrofit:2.9.0`
- `com.github.alexandre-g:AndroidPhotoshopColorPicker:1.2.4`
