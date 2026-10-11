#ifndef MIN_ISOLATION_FILTER_H
#define MIN_ISOLATION_FILTER_H
#include <stddef.h>
#include <stdint.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#if defined(__aarch64__)
#define MIN_NATIVE_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define MIN_NATIVE_ARCH AUDIT_ARCH_X86_64
#else
#error Min isolation supports only AArch64 and x86_64
#endif
size_t min_build_filter(struct sock_filter *out, size_t capacity, int offline);
int min_install_filter(int offline);
uint32_t min_filter_action(const struct seccomp_data *data, int offline);
#endif
