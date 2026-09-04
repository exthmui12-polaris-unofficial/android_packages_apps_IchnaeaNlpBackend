LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src/main/java)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/src/main/res
LOCAL_MANIFEST_FILE := src/main/AndroidManifest.xml
LOCAL_STATIC_JAVA_LIBRARIES := UnifiedNlpApi
LOCAL_PACKAGE_NAME := IchnaeaNlpBackend
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
