package com.darkyen.sqlite;

final class SQLiteNative {
    private SQLiteNative() {}

    static {
        System.loadLibrary("sqlite3l");
    }

    static native long nativeOpen(String path, int openFlags);
    static native void nativeClose(long connectionPtr);
    static native long nativePrepareStatement(long connectionPtr, String sql);
    static native void nativeFinalizeStatement(long connectionPtr, long statementPtr);
    static native void nativeBindNull(long connectionPtr, long statementPtr,
                                              int index);
    static native void nativeBindLong(long connectionPtr, long statementPtr,
                                              int index, long value);
    static native void nativeBindDouble(long connectionPtr, long statementPtr,
                                                int index, double value);
    static native void nativeBindString(long connectionPtr, long statementPtr,
                                                int index, String value);
    static native void nativeBindBlob(long connectionPtr, long statementPtr,
                                              int index, byte[] value);

    static native void nativeExecuteAndReset(long connectionPtr, long statementPtr);
    static native void nativeExecuteIgnoreAndReset(long connectionPtr, long statementPtr);
    static native long nativeExecuteForLongAndReset(long connectionPtr, long statementPtr, long defaultValue);
    static native double nativeExecuteForDoubleAndReset(long connectionPtr, long statementPtr, double defaultValue);
    static native String nativeExecuteForStringOrNullAndReset(long connectionPtr, long statementPtr);
    static native byte[] nativeExecuteForBlobOrNullAndReset(long connectionPtr, long statementPtr);
    static native long nativeExecuteForLastInsertedRowIDAndReset(long connectionPtr, long statementPtr);
    static native long nativeExecuteForChangedRowsAndReset(long connectionPtr, long statementPtr);

    static native boolean nativeCursorStep(long connectionPtr, long statementPtr);
    static native long nativeCursorGetLong(long connectionPtr, long statementPtr, int index);
    static native double nativeCursorGetDouble(long connectionPtr, long statementPtr, int index);
    static native String nativeCursorGetString(long connectionPtr, long statementPtr, int index);
    static native byte[] nativeCursorGetBlob(long connectionPtr, long statementPtr, int index);
    static native void nativeResetStatement(long connectionPtr, long statementPtr);
    static native void nativeClearBindings(long connectionPtr, long statementPtr);

    static native void nativeResetStatementAndClearBindings(long connectionPtr, long statementPtr);
    static native String nativeExecutePragma(long connectionPtr, String sql);
    static native void nativeInterrupt(long connectionPtr);
    static native int nativeReleaseMemory();
}
