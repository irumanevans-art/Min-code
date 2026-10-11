#ifndef MIN_ISOLATION_POLICY_H
#define MIN_ISOLATION_POLICY_H
#include "tracee/tracee.h"
extern int min_isolation_enabled;
extern int min_offline;
int min_set_mode(const char *value);
int min_add_writable(const char *value);
int min_check_syscall(Tracee *tracee);
int min_visible_path(Tracee *tracee, const char *host_path);
int min_guest_setup(void);
int min_trap_denied(Tracee *tracee);
#endif
