# Android SDKのコマンドラインツールをインストールする例
mkdir -p $HOME/android-sdk/cmdline-tools
cd $HOME/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip commandlinetools-linux-*.zip
mv cmdline-tools latest

# パスを通す（環境変数の設定）
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# ライセンスに自動で同意（これが大事！）
yes | sdkmanager --licenses
