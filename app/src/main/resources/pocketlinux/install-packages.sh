# Pocket Linux: コンテナの初期設定で、sudo と選んだものを入れる。root で、POSIX の sh で動かす。
#
# アプリ（ContainerSetup.kt）が、先頭に次の変数を置いてから動かす（1 なら入れる）:
#   POCKET_TOOLS     よく使うツール（curl・git・nano・less など）
#   POCKET_JAPANESE  日本語環境（ja_JP.UTF-8 の表示と日本時間）
#   POCKET_DEV       C・Java・Python の開発環境
#
# sudo を入れられなければ失敗で終わる（アプリはコンテナを root でログインするものにする）。
# 日本語環境・C・Java・Python は入れられなくても先へ進み、「!! 入れられませんでした: 名前」の行で知らせる。
# 「== 」で始まる行は、アプリの進み具合の表示にも使う。

: "${POCKET_TOOLS:=0}" "${POCKET_JAPANESE:=0}" "${POCKET_DEV:=0}"

if command -v apt-get >/dev/null 2>&1; then pm=apt
elif command -v apk >/dev/null 2>&1; then pm=apk
elif command -v dnf >/dev/null 2>&1; then pm=dnf
elif command -v microdnf >/dev/null 2>&1; then pm=microdnf
elif command -v zypper >/dev/null 2>&1; then pm=zypper
elif command -v pacman >/dev/null 2>&1; then pm=pacman
else
  echo "パッケージマネージャーが見つからないため、sudo を入れられませんでした" >&2
  exit 3
fi

# 引数のパッケージを入れる
pkg_install() {
  case $pm in
    apt) apt-get install -y --no-install-recommends "$@" ;;
    apk) apk add --no-cache "$@" ;;
    dnf) dnf install -y --setopt=install_weak_deps=False "$@" ;;
    microdnf) microdnf install -y "$@" ;;
    zypper) zypper --non-interactive install "$@" ;;
    pacman) pacman -S --noconfirm --needed "$@" ;;
  esac
}

unsupported() {
  echo "このディストリビューション（$pm）では、$1 を自動で入れられません" >&2
  return 1
}

skip() {
  echo "!! 入れられませんでした: $1" >&2
}

# ------------------------------------------------------------ sudo とよく使うツール

tools=""
if [ "$POCKET_TOOLS" = 1 ]; then
  case $pm in
    apk) tools="bash ca-certificates curl git nano less procps" ;;
    dnf | microdnf) tools="ca-certificates curl git nano less procps-ng" ;;
    *) tools="ca-certificates curl git nano less procps" ;;
  esac
fi

case $pm in
  apt)
    echo "== パッケージの一覧を更新しています"
    apt-get update || exit
    ;;
  pacman) pacman -Sy --noconfirm || exit ;;
esac
echo "== sudo などを入れています"
# shellcheck disable=SC2086 # パッケージ名を分けて渡す
pkg_install sudo $tools || exit
command -v sudo >/dev/null || exit 4

# ------------------------------------------------------------ 日本語環境

has_ja_locale() {
  locale -a 2>/dev/null | grep -qiE '^ja_JP\.utf-?8$'
}

setup_japanese() {
  case $pm in
    apt | apk | dnf | microdnf) ;;
    *) unsupported "日本語環境" || return 1 ;;
  esac
  echo "== 日本語環境を用意しています"
  # tzdata は入れるときに地域を聞いてくるので、先に日本時間を指しておく（DEBIAN_FRONTEND=noninteractive と合わせて聞かれない）
  ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime
  echo Asia/Tokyo >/etc/timezone
  case $pm in
    apt)
      if grep -qs '^ID=ubuntu' /etc/os-release; then
        # 訳文は language-pack-ja にある（Ubuntu は各パッケージから訳文を抜いて、言語ごとにまとめている）
        pkg_install locales tzdata language-pack-ja || return 1
        has_ja_locale || locale-gen ja_JP.UTF-8 || return 1
      else
        pkg_install locales tzdata || return 1
        if [ -f /etc/locale.gen ] && grep -q '^# *ja_JP\.UTF-8 UTF-8' /etc/locale.gen; then
          sed -i 's/^# *\(ja_JP\.UTF-8 UTF-8\)/\1/' /etc/locale.gen
        elif ! grep -qs '^ja_JP\.UTF-8 UTF-8' /etc/locale.gen; then
          echo 'ja_JP.UTF-8 UTF-8' >>/etc/locale.gen
        fi
        locale-gen || return 1
      fi
      ;;
    apk) pkg_install tzdata musl-locales || return 1 ;; # musl はロケールを作らなくても UTF-8 で動く
    dnf | microdnf) pkg_install tzdata glibc-langpack-ja || return 1 ;;
  esac
  [ -e /usr/share/zoneinfo/Asia/Tokyo ] || return 1
  if [ "$pm" != apk ]; then has_ja_locale || return 1; fi

  # ログインシェルで読み込まれる（イメージの ENV の LANG より後に読まれ、上書きする）
  cat >/etc/profile.d/pocket-linux-locale.sh <<'EOF'
# Pocket Linux が作成（日本語環境）
export LANG=ja_JP.UTF-8
EOF
  # su（PAM）が読む
  if [ "$pm" = apt ]; then echo 'LANG=ja_JP.UTF-8' >/etc/default/locale; fi
  return 0
}

# ------------------------------------------------------------ C・Java・Python

setup_c() {
  if command -v gcc >/dev/null 2>&1 && command -v make >/dev/null 2>&1 && [ -e /usr/include/stdio.h ]; then
    echo "== C（gcc）はもう入っています"
    return 0
  fi
  echo "== C（gcc・make）を入れています"
  case $pm in
    apt) pkg_install build-essential ;;
    apk) pkg_install build-base ;;
    dnf | microdnf) pkg_install gcc gcc-c++ make ;;
    *) unsupported "C" ;;
  esac || return 1
  command -v gcc >/dev/null 2>&1 && command -v make >/dev/null 2>&1
}

setup_java() {
  if command -v javac >/dev/null 2>&1; then
    echo "== Java（JDK）はもう入っています"
    return 0
  fi
  echo "== Java（JDK）を入れています。時間がかかります"
  case $pm in
    apt) pkg_install default-jdk-headless ;;
    apk) pkg_install openjdk21-jdk || pkg_install openjdk17-jdk ;;
    # java-devel はその版の標準の JDK（Fedora 45 なら 25）。java-latest は開発版のことがあるので最後の手段
    dnf | microdnf) pkg_install java-devel || pkg_install java-latest-openjdk-devel ;;
    *) unsupported "Java" ;;
  esac || return 1
  command -v javac >/dev/null 2>&1
}

python_ready() {
  # Debian 系は ensurepip を python3-venv に分けている（無いと venv を作れない）
  command -v python3 >/dev/null 2>&1 &&
    python3 -c 'import ensurepip, venv' >/dev/null 2>&1 &&
    python3 -m pip --version >/dev/null 2>&1
}

setup_python() {
  if python_ready; then
    echo "== Python はもう入っています"
    return 0
  fi
  echo "== Python（pip・venv）を入れています"
  case $pm in
    apt) pkg_install python3 python3-pip python3-venv ;;
    apk) pkg_install python3 py3-pip ;;
    dnf | microdnf) pkg_install python3 python3-pip ;;
    *) unsupported "Python" ;;
  esac || return 1
  # python でも動くように（無い版のディストリビューションでは入れない）
  if [ "$pm" = apt ] && ! command -v python >/dev/null 2>&1; then
    pkg_install python-is-python3 >/dev/null 2>&1 || true
  fi
  python_ready
}

# ------------------------------------------------------------

if [ "$POCKET_JAPANESE" = 1 ]; then
  setup_japanese || skip "日本語環境"
fi
if [ "$POCKET_DEV" = 1 ]; then
  setup_c || skip "C"
  setup_java || skip "Java"
  setup_python || skip "Python"
fi
# 落としたパッケージを消して容量を空ける
if [ "$pm" = apt ]; then apt-get clean; fi
echo "== 完了"
exit 0
