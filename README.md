# Stack-chan Watch

Pixel Watch 4（Wear OS）向けの Stack-chan アバターアプリ。
腕時計の中で Stack-chan が顔を出して、まばたきや呼吸をするのが目標です。

## 設計の参考

- [stack-chan/m5stack-avatar](https://github.com/stack-chan/m5stack-avatar)（MIT）
  表情（Expression 6種）と顔パーツ構成の参考
- [stack-chan/stack-chan](https://github.com/stack-chan/stack-chan)（Apache-2.0）
  FaceState（顔の状態オブジェクト）＋ Motions（まばたき・呼吸・視線の合成）の設計と
  パラメータ（まばたき間隔など）の参考

コードはどちらも流用せず、Kotlin / Jetpack Compose で新規実装しています。

## ビルド

ビルドは GitHub Actions で行います（`.github/workflows/build.yml`）。
push すると `app-debug.apk` が成果物（Artifacts）として生成されます。

## 実機へのインストール

1. Pixel Watch 4 で開発者オプションを有効化し、「Wi-Fi デバッグ」を ON にする
2. Mac からペアリング: `adb pair <ウォッチのIP>:<ペアリングポート>`
3. 接続: `adb connect <ウォッチのIP>:<ポート>`
4. インストール: `adb install app-debug.apk`

※ デバッグ APK の署名はビルド環境ごとに変わるため、上書きインストールに失敗する
場合は先にアンインストールしてください。
