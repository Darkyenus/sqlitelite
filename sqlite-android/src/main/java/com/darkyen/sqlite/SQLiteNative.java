package com.darkyen.sqlite;

public final class SQLiteNative {
    private SQLiteNative() {}

    static {
        System.loadLibrary("sqlite3l");
    }

    public static native long nativeOpen(String path, int openFlags);
    public static native void nativeClose(long connectionPtr);
    public static native long nativePrepareStatement(long connectionPtr, String sql);
    public static native void nativeFinalizeStatement(long connectionPtr, long statementPtr);
    public static native int nativeGetParameterCount(long connectionPtr, long statementPtr);
    public static native void nativeBindNull(long connectionPtr, long statementPtr,
                                              int index);
    public static native void nativeBindLong(long connectionPtr, long statementPtr,
                                              int index, long value);
    public static native void nativeBindDouble(long connectionPtr, long statementPtr,
                                                int index, double value);
    public static native void nativeBindString(long connectionPtr, long statementPtr,
                                                int index, String value);
    public static native void nativeBindBlob(long connectionPtr, long statementPtr,
                                              int index, byte[] value);

    public static native void nativeExecuteAndReset(long connectionPtr, long statementPtr);
    public static native void nativeExecuteIgnoreAndReset(long connectionPtr, long statementPtr);
    public static native long nativeExecuteForLongAndReset(long connectionPtr, long statementPtr, long defaultValue);
    public static native double nativeExecuteForDoubleAndReset(long connectionPtr, long statementPtr, double defaultValue);
    public static native String nativeExecuteForStringOrNullAndReset(long connectionPtr, long statementPtr);
    public static native byte[] nativeExecuteForBlobOrNullAndReset(long connectionPtr, long statementPtr);
    public static native long nativeExecuteForLastInsertedRowIDAndReset(long connectionPtr, long statementPtr);
    public static native long nativeExecuteForChangedRowsAndReset(long connectionPtr, long statementPtr);

    public static native boolean nativeCursorStep(long connectionPtr, long statementPtr);
    public static native long nativeCursorGetLong(long connectionPtr, long statementPtr, int index);
    public static native double nativeCursorGetDouble(long connectionPtr, long statementPtr, int index);
    public static native String nativeCursorGetString(long connectionPtr, long statementPtr, int index);
    public static native byte[] nativeCursorGetBlob(long connectionPtr, long statementPtr, int index);
    public static native void nativeResetStatement(long connectionPtr, long statementPtr);
    public static native void nativeClearBindings(long connectionPtr, long statementPtr);

    public static native void nativeResetStatementAndClearBindings(long connectionPtr, long statementPtr);
    public static native void nativeExecute(long connectionPtr, long statementPtr);
    public static native String nativeExecutePragma(long connectionPtr, String sql);
    public static native long nativeExecuteForLong(long connectionPtr, long statementPtr);
    public static native String nativeExecuteForString(long connectionPtr, long statementPtr);
    public static native int nativeExecuteForChangedRowCount(long connectionPtr, long statementPtr);
    public static native long nativeExecuteForLastInsertedRowId(
            long connectionPtr, long statementPtr);
    public static native long nativeExecuteForCursorWindow(
            long connectionPtr, long statementPtr, long winPtr,
            int startPos, int requiredPos, boolean countAllRows);
    public static native void nativeInterrupt(long connectionPtr);
    public static native int nativeReleaseMemory();
}
