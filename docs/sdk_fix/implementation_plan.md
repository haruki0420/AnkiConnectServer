# Android SDK 認識問題の修正

## 概要
Android SDKが認識されない問題を解決するため、`local.properties` ファイルを適切な形式で再生成します。

## 変更内容
### [Component Name]
#### [MODIFY] [local.properties](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/local.properties)
- バックスラッシュによるエスケープを避け、Gradleが解釈しやすいスラッシュ `/` 区切りのパス `sdk.dir=C:/Users/kuni/AppData/Local/Android/Sdk` に変更しました。

## 検証計画
### 自動テスト
- `./gradlew.bat help` を実行し、プロジェクトの評価が成功することを確認します。
