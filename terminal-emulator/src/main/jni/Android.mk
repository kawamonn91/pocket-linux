LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Android 15 以降の 16KB ページの端末でも読み込めるようにする（Pocket Linux で追加）
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
