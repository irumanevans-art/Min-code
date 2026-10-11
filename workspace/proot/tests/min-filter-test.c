#include "isolation/filter.h"
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static int failures;
static unsigned evaluate(const struct sock_filter *b, size_t n, struct seccomp_data d) {
    unsigned a=0;
    for(size_t pc=0;pc<n;pc++) {
        switch(b[pc].code) {
        case BPF_LD|BPF_W|BPF_ABS: memcpy(&a,((char*)&d)+b[pc].k,4);break;
        case BPF_JMP|BPF_JEQ|BPF_K:pc+=a==b[pc].k?b[pc].jt:b[pc].jf;break;
        case BPF_JMP|BPF_JGE|BPF_K:pc+=a>=b[pc].k?b[pc].jt:b[pc].jf;break;
        case BPF_JMP|BPF_JSET|BPF_K:pc+=(a&b[pc].k)?b[pc].jt:b[pc].jf;break;
        case BPF_RET|BPF_K:return b[pc].k;
        default: return 0;
        }
    }
    return 0;
}
static void check(const char *label,int nr,unsigned long long arg,int offline,unsigned arch,unsigned expected) {
    struct sock_filter b[256];
    struct seccomp_data d={.nr=nr,.arch=arch,.args={arg}};
    size_t n=min_build_filter(b,256,offline);
    unsigned result=evaluate(b,n,d);
    printf("%s mode=%d action=%x expected=%x %s\n",label,offline,result,expected,result==expected?"PASS":"FAIL");
    if(result!=expected)failures++;
}
#define DENY(name) check(#name,SYS_##name,0,0,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM)
int main(void) {
    DENY(ptrace);DENY(mount);DENY(umount2);DENY(pivot_root);DENY(unshare);DENY(setns);
    DENY(bpf);DENY(perf_event_open);DENY(keyctl);DENY(add_key);DENY(request_key);
    DENY(userfaultfd);DENY(process_vm_readv);DENY(process_vm_writev);DENY(pidfd_getfd);
    DENY(io_uring_setup);DENY(io_uring_enter);DENY(io_uring_register);
    DENY(init_module);DENY(finit_module);DENY(delete_module);DENY(kexec_load);DENY(reboot);
    DENY(swapon);DENY(swapoff);DENY(seccomp);DENY(open_by_handle_at);
    DENY(pidfd_open);DENY(pidfd_send_signal);DENY(process_madvise);DENY(name_to_handle_at);
    DENY(open_tree);DENY(move_mount);DENY(fsopen);DENY(fsconfig);DENY(fsmount);DENY(fspick);DENY(mount_setattr);
    DENY(fchmodat2);DENY(sendmmsg);
#ifdef SYS_kexec_file_load
    DENY(kexec_file_load);
#endif
#ifdef SYS_setxattrat
    DENY(setxattrat);DENY(removexattrat);
#endif
    check("prctl-lock",SYS_prctl,PR_SET_SECCOMP,0,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("prctl-get",SYS_prctl,PR_GET_SECCOMP,1,MIN_NATIVE_ARCH,SECCOMP_RET_ALLOW);
    check("ipv4",SYS_socket,AF_INET,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("ipv6",SYS_socket,AF_INET6,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("unix",SYS_socket,AF_UNIX,1,MIN_NATIVE_ARCH,SECCOMP_RET_ALLOW);
    check("proxy-ipv4",SYS_socket,AF_INET,0,MIN_NATIVE_ARCH,SECCOMP_RET_ALLOW);
    check("socketpair-ipv4",SYS_socketpair,AF_INET,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("packet",SYS_socket,AF_PACKET,0,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("foreign-abi",SYS_read,0,1,AUDIT_ARCH_I386,SECCOMP_RET_ERRNO|EPERM);
#ifdef __x86_64__
    check("x32",0x40000000,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
#endif
    check("exec",SYS_execve,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ALLOW);
    check("clone",SYS_clone,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ALLOW);
    check("clone-untraced",SYS_clone,0x00800011,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("clone3",SYS_clone3,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|ENOSYS);
    check("new-setxattrat",463,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("new-removexattrat",466,0,1,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    check("future-syscall",999,0,0,MIN_NATIVE_ARCH,SECCOMP_RET_ERRNO|EPERM);
    printf("FAILURES=%d\n",failures);return failures?1:0;
}
