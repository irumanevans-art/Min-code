#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <stddef.h>
#include <unistd.h>
#include <stdio.h>
int main(int argc,char **argv) {
    if(argc<2)return 2;
    struct sock_filter b[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mount,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_unshare,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_io_uring_setup,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog p={sizeof(b)/sizeof(b[0]),b};
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)||prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p)){perror("outer trap");return 2;}
    execv(argv[1],argv+1);perror("exec");return 2;
}
