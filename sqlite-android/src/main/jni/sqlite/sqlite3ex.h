
#include "sqlite3.h"

// Add one extra API function to reset the error code,
// because that is missing in the official API and would solve mess around
// sqlite3_column errors.
// See https://sqlite-users.sqlite.narkive.com/XyXMR7gF/sqlite3-reset-or-sqlite3-finalize-and-error-reporting
#ifdef __cplusplus
extern "C" {
#endif

SQLITE_API void sqlite3ex_clear_errcode(sqlite3 *db);

#ifdef __cplusplus
}
#endif
