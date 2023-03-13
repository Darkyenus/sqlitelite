#include "sqlite3.c"

#if SQLITE_ATOMIC_INTRINSICS != 1
#error "Missing atomics!"
#endif

#if !HAVE_USLEEP
#error "Missing USLEEP!"
#endif

SQLITE_API void sqlite3ex_clear_errcode(sqlite3 *db) {
    // Yes, this is a hack, but they already have this function, I just want to call it!
    if (db) sqlite3ErrorClear(db);
}