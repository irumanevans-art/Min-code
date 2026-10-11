#include "isolation/filter.h"
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <errno.h>
#include <unistd.h>

/* 用本机头文件编号，避免把 arm64 的调用号误用到 x86_64。 */
static const unsigned denied_calls[] = {
    SYS_ptrace, SYS_mount, SYS_umount2, SYS_pivot_root, SYS_unshare, SYS_setns,
    SYS_bpf, SYS_perf_event_open, SYS_keyctl, SYS_add_key, SYS_request_key,
    SYS_userfaultfd, SYS_process_vm_readv, SYS_process_vm_writev,
    SYS_pidfd_getfd, SYS_pidfd_open, SYS_pidfd_send_signal, SYS_process_madvise,
    SYS_io_uring_setup, SYS_io_uring_enter, SYS_io_uring_register,
    SYS_init_module, SYS_finit_module, SYS_delete_module, SYS_kexec_load,
#ifdef SYS_kexec_file_load
    SYS_kexec_file_load,
#endif
    SYS_reboot, SYS_swapon, SYS_swapoff, SYS_seccomp,
    SYS_open_by_handle_at, SYS_name_to_handle_at,
    SYS_open_tree, SYS_move_mount, SYS_fsopen, SYS_fsconfig, SYS_fsmount,
    SYS_fspick, SYS_mount_setattr,
    /* 未经路径翻译的新写入 ABI 不能直接落到宿主。 */
    SYS_fchmodat2,
    SYS_sendmmsg,
};

size_t min_build_filter(struct sock_filter *out, size_t capacity, int offline) {
    size_t n=0, count=sizeof(denied_calls)/sizeof(denied_calls[0]);
    if (capacity < 32+count*2) return 0;
#define STMT(code,k) out[n++]=(struct sock_filter)BPF_STMT(code,k)
#define JUMP(code,k,t,f) out[n++]=(struct sock_filter)BPF_JUMP(code,k,t,f)
#define DENY() STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM)
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch));
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,MIN_NATIVE_ARCH,1,0); DENY();
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
#ifdef __x86_64__
    /* x32 与 x86_64 共用 arch，须另外拒绝编号高位。 */
    JUMP(BPF_JMP|BPF_JGE|BPF_K,0x40000000,0,1); DENY();
#endif
    /* NDK 28 的编号表止于 462。新 ABI（如 *xattrat）未经翻译，一律拒绝而不是静默放行。 */
    JUMP(BPF_JMP|BPF_JGE|BPF_K,463,0,1);DENY();
    for(size_t i=0;i<count;i++) { JUMP(BPF_JMP|BPF_JEQ|BPF_K,denied_calls[i],0,1);DENY(); }
    /* clone3 的 flags 是指针，返回 ENOSYS 让 libc 退回可按值过滤的 clone。 */
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone3,0,1);STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOSYS);
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,0,3);
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    /* CLONE_UNTRACED 会让子进程脱离 L1，必须在内核按值拒绝。 */
    JUMP(BPF_JMP|BPF_JSET|BPF_K,0x00800000,0,1);DENY();
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,3);
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,PR_SET_SECCOMP,0,1);DENY();
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,1,0);
    JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socketpair,0,offline?3:5);
    STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    if(offline) {
        JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_UNIX,1,0);DENY();
    } else {
        JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_UNIX,3,0);
        JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_INET,2,0);
        JUMP(BPF_JMP|BPF_JEQ|BPF_K,AF_INET6,1,0);DENY();
    }
    STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
#undef DENY
#undef JUMP
#undef STMT
    return n;
}
int min_install_filter(int offline) {
    struct sock_filter b[256];
    size_t count=min_build_filter(b,256,offline);
    if(!count){errno=EINVAL;return -1;}
    struct sock_fprog p={.len=(unsigned short)count,.filter=b};
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))return -1;
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p);
}

uint32_t min_filter_action(const struct seccomp_data *data,int offline) {
    struct sock_filter b[256];size_t n=min_build_filter(b,256,offline);
    uint32_t a=0;
    /* Android 的外层 TRAP 比 ERRNO 优先；SIGSYS 仿真必须消费同一份规则，不能假成功。 */
    for(size_t pc=0;pc<n;pc++) {
        switch(b[pc].code) {
        case BPF_LD|BPF_W|BPF_ABS: __builtin_memcpy(&a,((const char*)data)+b[pc].k,4);break;
        case BPF_JMP|BPF_JEQ|BPF_K:pc+=a==b[pc].k?b[pc].jt:b[pc].jf;break;
        case BPF_JMP|BPF_JGE|BPF_K:pc+=a>=b[pc].k?b[pc].jt:b[pc].jf;break;
        case BPF_JMP|BPF_JSET|BPF_K:pc+=(a&b[pc].k)?b[pc].jt:b[pc].jf;break;
        case BPF_RET|BPF_K:return b[pc].k;
        default:return SECCOMP_RET_ERRNO|EPERM;
        }
    }
    return SECCOMP_RET_ERRNO|EPERM;
}
