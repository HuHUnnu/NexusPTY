package com.chaoxing.eduapp

object NativePty {
    init {
        System.loadLibrary("educore")
    }

    /**
     * @return IntArray[0] = master fd, IntArray[1] = child pid
     */
    @JvmStatic
    external fun nativeCreateSubprocess(
        cmd: String, args: Array<String>?, envVars: Array<String>?,
        rows: Int, cols: Int
    ): IntArray

    @JvmStatic external fun nativeRead(fd: Int, buffer: ByteArray, len: Int): Int
    @JvmStatic external fun nativeWrite(fd: Int, data: ByteArray, len: Int): Int
    @JvmStatic external fun nativeClose(fd: Int)
    @JvmStatic external fun nativeWaitFor(pid: Int): Int
    @JvmStatic external fun nativeSetSize(fd: Int, rows: Int, cols: Int)
    @JvmStatic external fun nativeKill(pid: Int, signal: Int)
}
