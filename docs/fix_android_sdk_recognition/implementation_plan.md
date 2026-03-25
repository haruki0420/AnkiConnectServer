# Android SDK 認識問題の修正計画

Android標準クラスが認識されない問題を解決するため、SDKパスの検証とプロジェクト構成ファイルのクリーンアップを実施します。

## プロジェクト構成

### [Android Project Core]

#### [MODIFY] [local.properties](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/local.properties)
- `sdk.dir` のパスが正しいか再確認します。

#### [DELETE] [.gradle](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/.gradle)
- 古いビルドキャッシュを削除します。

#### [DELETE] [.idea](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/.idea)
- IDEの設定ファイルを削除し、再インポート時に再生成させます。

## 検証プラン

### 自動テスト
- `./gradlew clean` を実行し、ビルドキャッシュをクリアします。
- `./gradlew assembleDebug` (または適当なタスク) を実行し、SDKが正しく認識されているか確認します。

### 手動検証
- エディタ上の波線が消え、`Service` や `Uri` などの標準クラスが認識されることをユーザーに確認してもらいます。
