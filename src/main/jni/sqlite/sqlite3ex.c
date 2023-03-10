#include "sqlite3.c"

SQLITE_API void sqlite3ex_clear_errcode(sqlite3 *db) {
    // Yes, this is a hack, but they already have this function, I just want to call it!
    if (db) sqlite3ErrorClear(db);
}