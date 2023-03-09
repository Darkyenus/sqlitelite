LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# NOTE the following flags,
#   SQLITE_TEMP_STORE=3 causes all TEMP files to go into RAM. and thats the behavior we want
#   SQLITE_ENABLE_FTS3   enables usage of FTS3 - NOT FTS1 or 2.
#   SQLITE_DEFAULT_AUTOVACUUM=1  causes the databases to be subject to auto-vacuum
sqlite_flags := \
	-DNDEBUG=1 \
	-DHAVE_USLEEP=1 \
	-DHAVE_ISNAN=1 \
	-DSQLITE_DQS=0 \
	-DSQLITE_THREADSAFE=2 \
    -DSQLITE_DEFAULT_MEMSTATUS=0 \
    -DSQLITE_LIKE_DOESNT_MATCH_BLOBS \
    -DSQLITE_MAX_EXPR_DEPTH=0 \
    -DSQLITE_OMIT_DECLTYPE \
    -DSQLITE_OMIT_DEPRECATED \
    -DSQLITE_OMIT_PROGRESS_CALLBACK \
    -DSQLITE_OMIT_SHARED_CACHE \
    -DSQLITE_USE_ALLOCA \
    -DSQLITE_OMIT_AUTOINIT \
	-DSQLITE_DEFAULT_FILE_PERMISSIONS=0600 \
	-DSQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576 \
	-DSQLITE_DEFAULT_LOCKING_MODE=1 \
    -DSQLITE_DEFAULT_SYNCHRONOUS=0 \
    -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=0 \
	-DSQLITE_TEMP_STORE=3 \
	-DSQLITE_POWERSAFE_OVERWRITE=1 \
	-DSQLITE_DEFAULT_AUTOVACUUM=1 \
	-DSQLITE_ENABLE_MEMORY_MANAGEMENT=1 \
	-DSQLITE_OMIT_JSON \
	-DSQLITE_UNTESTABLE \
	-DSQLITE_OMIT_COMPILEOPTION_DIAGS \
    -DSQLITE_ENABLE_BATCH_ATOMIC_WRITE \
    -DSQLITE_DISABLE_DIRSYNC \
    -DSQLITE_OMIT_DESERIALIZE \
    -DSQLITE_OMIT_TRACE \
    -O3

LOCAL_CFLAGS += $(sqlite_flags)
LOCAL_CFLAGS += -Wno-unused-parameter -Wno-int-to-pointer-cast
LOCAL_CFLAGS += -Wno-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null -Wunused


ifeq ($(TARGET_ARCH), arm)
	LOCAL_CFLAGS += -DPACKED="__attribute__ ((packed))"
else
	LOCAL_CFLAGS += -DPACKED=""
endif

LOCAL_SRC_FILES:= \
	android_database_SQLiteCommon.cpp \
	SQLiteNative.cpp \
	android_database_CursorWindow.cpp \
	CursorWindow.cpp \
	JNIHelp.cpp \
	JNIString.cpp

LOCAL_SRC_FILES += sqlite3ex.c

LOCAL_C_INCLUDES += $(LOCAL_PATH)

LOCAL_MODULE:= libsqlite3l
LOCAL_LDLIBS += -ldl -llog -latomic

include $(BUILD_SHARED_LIBRARY)

