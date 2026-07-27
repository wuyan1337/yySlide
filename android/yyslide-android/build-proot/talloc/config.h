#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_FUNCTION_ATTRIBUTE_FORMAT 1
