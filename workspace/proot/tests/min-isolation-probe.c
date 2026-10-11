#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/mount.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/un.h>

static int failures;
static void denied(const char *name, long result, int expected) {
    int error = errno;
    printf("%s result=%ld errno=%d expected=%d %s\n", name, result, error, expected,
           result == -1 && error == expected ? "PASS" : "FAIL");
    if (result != -1 || error != expected) failures++;
}
static void danger(void) {
    errno=0; denied("ptrace", syscall(SYS_ptrace, PTRACE_TRACEME, 0, 0, 0), EPERM);
    errno=0; denied("mount", syscall(SYS_mount, "none", "/tmp", "tmpfs", 0, 0), EPERM);
    errno=0; denied("unshare", syscall(SYS_unshare, CLONE_NEWUSER), EPERM);
    errno=0; denied("seccomp", syscall(SYS_seccomp, SECCOMP_GET_ACTION_AVAIL, 0, 0), EPERM);
    errno=0; denied("prctl-seccomp", prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, 0), EPERM);
    errno=0; denied("io-uring", syscall(SYS_io_uring_setup, 0, 0), EPERM);
    errno=0; denied("pidfd-getfd", syscall(SYS_pidfd_getfd, -1, 0, 0), EPERM);
}
static void paths(void) {
    const char *targets[]={"/etc/min-isolation-outside", "/root/min-isolation-outside", "/workspace/../min-isolation-outside"};
    for (unsigned i=0;i<sizeof(targets)/sizeof(targets[0]);i++) {
        errno=0; int fd=open(targets[i],O_WRONLY|O_CREAT|O_TRUNC,0600);
        denied(targets[i],fd,EACCES); if(fd>=0) {close(fd);unlink(targets[i]);}
    }
    unlink("/workspace/min-isolation-link"); symlink("/etc", "/workspace/min-isolation-link");
    errno=0; int fd=open("/workspace/min-isolation-link/min-isolation-outside",O_WRONLY|O_CREAT,0600);
    denied("symlink-escape",fd,EACCES); if(fd>=0){close(fd);unlink("/etc/min-isolation-outside");}
    unlink("/workspace/min-isolation-link");
    fd=open("/workspace/min-isolation-allowed",O_WRONLY|O_CREAT|O_TRUNC,0600);
    printf("workspace-write %s\n",fd>=0 ? "PASS":"FAIL"); if(fd<0) failures++;
    else {write(fd,"ok",2);close(fd);}
    errno=0; denied("rename-outside",rename("/workspace/min-isolation-allowed","/etc/min-isolation-outside"),EACCES);
    unlink("/workspace/min-isolation-allowed"); unlink("/etc/min-isolation-outside");
}
static void net(void) {
    errno=0; int fd=socket(AF_INET,SOCK_STREAM,0); denied("socket-ipv4",fd,EPERM); if(fd>=0)close(fd);
    errno=0; fd=socket(AF_INET6,SOCK_DGRAM,0); denied("socket-ipv6",fd,EPERM); if(fd>=0)close(fd);
    fd=socket(AF_UNIX,SOCK_STREAM,0); printf("socket-unix %s\n",fd>=0?"PASS":"FAIL"); if(fd<0)failures++;else close(fd);
}
static void proc(const char *pid) {
    char path[128];snprintf(path,sizeof(path),"/proc/%s/status",pid);
    errno=0;int fd=open(path,O_RDONLY);denied("foreign-proc",fd,EACCES);if(fd>=0)close(fd);
    fd=open("/proc/self/status",O_RDONLY);printf("self-proc %s\n",fd>=0?"PASS":"FAIL");if(fd<0)failures++;else close(fd);
    const char *sensitive[]={"/proc/self/mem","/proc/self/root","/proc/thread-self/root"};
    for(unsigned i=0;i<sizeof(sensitive)/sizeof(sensitive[0]);i++){
        errno=0;fd=open(sensitive[i],O_RDONLY);denied(sensitive[i],fd,EACCES);if(fd>=0)close(fd);
    }
}
static void extras(const char *pid) {
    int fd=open("/etc/min-isolation-fd-fixture",O_RDONLY);struct stat info;
    if(fd<0||fstat(fd,&info)){printf("fd-fixture result=%d errno=%d FAIL\n",fd,errno);failures++;if(fd>=0)close(fd);return;}
    /* 用相同权限值做无副作用探针，避免红测改动已有 rootfs 文件。 */
    errno=0;denied("fd-metadata-outside",fchmod(fd,info.st_mode&0777),EACCES);
    errno=0;denied("fd-write-outside",pwrite(fd,"x",1,0),EACCES);close(fd);
    errno=0;denied("host-signal-probe",kill(atoi(pid),0),EPERM);
    int pair[2];socketpair(AF_UNIX,SOCK_DGRAM|SOCK_NONBLOCK,0,pair);
    char control[128],data;struct iovec iov={&data,1};
    struct msghdr message={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    errno=0;denied("receive-rights",recvmsg(pair[0],&message,0),EACCES);
    close(pair[0]);close(pair[1]);
    unlink("/workspace/min-isolation-hardlink");
    errno=0;denied("readonly-hardlink",link("/etc/min-isolation-fd-fixture","/workspace/min-isolation-hardlink"),EACCES);
    unlink("/workspace/min-isolation-hardlink");
}
static void binds(void) {
    struct sockaddr_un unix_address={.sun_family=AF_UNIX};
    const char *paths[]={"/etc/min-isolation-socket","/workspace/min-isolation-socket"};
    for(unsigned i=0;i<2;i++){
        int fd=socket(AF_UNIX,SOCK_STREAM,0);unlink(paths[i]);
        strcpy(unix_address.sun_path,paths[i]);errno=0;
        int r=bind(fd,(struct sockaddr*)&unix_address,sizeof(unix_address));
        if(!i)denied("unix-bind-outside",r,EACCES);
        else {printf("unix-bind-workspace %s\n",r==0?"PASS":"FAIL");if(r)failures++;}
        close(fd);unlink(paths[i]);
    }
    int fd=socket(AF_INET,SOCK_STREAM,0);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    errno=0;denied("proxy-mode-inbound-bind",bind(fd,(struct sockaddr*)&address,sizeof(address)),EACCES);
    close(fd);
}
static void tree(void) {
    pid_t child=fork();
    if(child==0) {
        setsid();pid_t grandchild=fork();
        if(grandchild==0){for(;;)pause();}
        int fd=open("/workspace/min-isolation-tree.txt",O_WRONLY|O_CREAT|O_TRUNC,0600);
        dprintf(fd,"%d %d %d\n",getppid(),getpid(),grandchild);close(fd);
        for(;;)pause();
    }
    for(;;)pause();
}
int main(int argc,char **argv) {
    if(argc<2)return 2;
    if(!strcmp(argv[1],"danger"))danger();
    else if(!strcmp(argv[1],"paths"))paths();
    else if(!strcmp(argv[1],"net"))net();
    else if(!strcmp(argv[1],"proc")&&argc==3)proc(argv[2]);
    else if(!strcmp(argv[1],"extras")&&argc==3)extras(argv[2]);
    else if(!strcmp(argv[1],"binds"))binds();
    else if(!strcmp(argv[1],"tree"))tree();
    else return 2;
    printf("FAILURES=%d\n",failures);return failures?1:0;
}
