# YTFlow

Android 上で快適に YouTube を視聴するための専用ブラウザアプリです。
Mozilla の強力なブラウザエンジン「GeckoView」をベースにしており、チャット欄や不要な要素を自動的に整理・クリーンアップし、ライブチャットのオーバーレイ表示などをサポートします。

## 主な機能
- **ピクチャーインピクチャー (PiP)**: 他のアプリを使いながら小窓で動画を視聴できます。
- **LiveChat Flusher の統合**: ライブ配信のチャットをニコニコ動画のように動画上に流す拡張機能を標準搭載しています。
- **公式アプリとのシームレスな連携**: 視聴中の動画の「続き（秒数付き）」からワンタップで YouTube 公式アプリを開けます。

## ビルドとインストール方法

本プロジェクトは Android Studio を使用してビルドできます。

```bash
git clone https://github.com/kaikokusann/ytflow-android.git
cd ytflow-android
./gradlew installDebug
```

## サードパーティライセンスについて

本アプリは、以下のサードパーティ製拡張機能を含んでいます。

- **YouTube LiveChat Flusher**: 
  - 作者: [_y_s (ys-j)](https://github.com/ys-j)
  - ライセンス: [Mozilla Public License 2.0 (MPL-2.0)](https://www.mozilla.org/en-US/MPL/2.0/)
  - URL: https://github.com/ys-j/YoutubeLiveChatFlusher

LiveChat Flusher のソースコード（本リポジトリの `app/src/main/assets/extensions/live_chat_flusher` 内）は、MPL-2.0 ライセンスの下で提供されており、同ライセンスの規定に基づいて改変・再配布されています。

## ライセンス (License)

本アプリの独自コード（Kotlin部分）については、[MIT License](LICENSE) の下で公開されています。
詳細は `LICENSE` ファイルをご確認ください。
