#define _GNU_SOURCE
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <limits.h>
#include <dirent.h>
#include "isolation/policy.h"
#include "isolation/filter.h"
#include "tracee/reg.h"
#include "tracee/mem.h"
#include "tracee/abi.h"
#include "syscall/sysnum.h"
#include "path/path.h"

int min_isolation_enabled;
int min_offline=1;
static unsigned short ports[8];
static size_t port_count;
static char roots[16][PATH_MAX];
static size_t root_count;

int min_set_mode(const char *value) {
    if(min_isolation_enabled)return -EINVAL;
    if(!strcmp(value,"offline")){min_isolation_enabled=1;return 0;}
    if(strncmp(value,"proxy:",6))return -EINVAL;
    const char *p=value+6;
    do {
        char *end;unsigned long port=strtoul(p,&end,10);
        if(end==p||port<1||port>65535||port_count==8||(*end&&*end!=','))return -EINVAL;
        ports[port_count++]=(unsigned short)port;
        p=*end?end+1:end;
    }while(*p);
    if(!port_count)return -EINVAL;
    min_offline=0;min_isolation_enabled=1;return 0;
}
int min_add_writable(const char *value) {
    if(root_count==16||value[0]!='/')return -EINVAL;
    if(!realpath(value,roots[root_count]))return -errno;
    if(!strcmp(roots[root_count],"/"))return -EINVAL;
    root_count++;return 0;
}
static int under(const char *path,const char *root) {
    size_t n=strlen(root);return !strncmp(path,root,n)&&(path[n]==0||path[n]=='/');
}
static int writable(const char *host) {
    for(size_t i=0;i<root_count;i++)if(under(host,roots[i]))return 0;
    /* 这些设备是 shell 的标准流与随机源，不授予 /dev 的目录写权限。 */
    const char *devs[]={"/dev/null","/dev/zero","/dev/random","/dev/urandom","/dev/tty"};
    for(size_t i=0;i<sizeof(devs)/sizeof(devs[0]);i++)if(!strcmp(host,devs[i]))return 0;
    if(under(host,"/dev/pts")&&strcmp(host,"/dev/pts"))return 0;
    return -EACCES;
}
int min_visible_path(Tracee *tracee,const char *host) {
    if(!min_isolation_enabled)return 0;
    if(!strncmp(host,"/proc/",6)) {
        const char *p=host+6;char *end;
        if(!strncmp(p,"self",4)&&(p[4]==0||p[4]=='/'))return 0;
        if(!strncmp(p,"thread-self",11)&&(p[11]==0||p[11]=='/'))return 0;
        long pid=strtol(p,&end,10);
        if(end!=p&&(*end==0||*end=='/')) {
            Tracee *member=get_tracee(tracee,(pid_t)pid,false);
            if(!member||member->terminated)return tracee->min_denial=-EACCES;
            /* 即便是自己，也不能从 mem/root/fd 路径拿到跳过翻译的宿主入口。 */
            if(under(end,"/mem")||under(end,"/root"))return tracee->min_denial=-EACCES;
        }
    }
    return 0;
}
static int path_arg(Tracee *t,Reg arg,int dirfd,int follow) {
    char guest[PATH_MAX],host[PATH_MAX];
    word_t address=peek_reg(t,ORIGINAL,arg);
    if(!address)return -EFAULT;
    int r=read_string(t,guest,address,sizeof(guest));
    if(r<0)return r;
    if(!guest[0]&&dirfd!=AT_FDCWD) {
        r=readlink_proc_pid_fd(t->pid,dirfd,host);
    } else r=translate_path(t,host,dirfd,guest,follow);
    if(r<0)return r;
    return writable(host);
}
static int fd_arg(Tracee *t,Reg arg) {
    char path[PATH_MAX];int r=readlink_proc_pid_fd(t->pid,(int)peek_reg(t,ORIGINAL,arg),path);
    if(r<0)return r;
    /* pipe/socket/memfd 不在文件树中；新进程不继承宿主 fd，连接另由目标检查约束。 */
    if(path[0]!='/')return 0;
    return writable(path);
}
static int sockaddr_arg(Tracee *t,word_t addr,word_t len) {
    struct sockaddr_storage s={0};
    if(!addr||len<sizeof(sa_family_t)||len>sizeof(s))return -EACCES;
    int r=read_data(t,&s,addr,len);if(r<0)return r;
    if(s.ss_family==AF_UNIX)return 0;
    if(min_offline)return -EPERM;
    unsigned short port;
    if(s.ss_family==AF_INET&&len>=sizeof(struct sockaddr_in)) {
        struct sockaddr_in *v=(void*)&s;
        if(v->sin_addr.s_addr!=htonl(INADDR_LOOPBACK))return -EACCES;
        port=ntohs(v->sin_port);
    }else if(s.ss_family==AF_INET6&&len>=sizeof(struct sockaddr_in6)) {
        struct sockaddr_in6 *v=(void*)&s;
        if(!IN6_IS_ADDR_LOOPBACK(&v->sin6_addr))return -EACCES;
        port=ntohs(v->sin6_port);
    }else return -EACCES;
    for(size_t i=0;i<port_count;i++)if(ports[i]==port)return 0;
    return -EACCES;
}
static int signal_target(Tracee *t,long pid) {
    /* 同 UID 的主进程不属于 guest 树；信号不能停掉 Min 或代理。 */
    Tracee *member=pid>0?get_tracee(t,(pid_t)pid,false):NULL;
    return member&&!member->terminated?0:-EPERM;
}
static int bind_arg(Tracee *t,word_t addr,word_t len) {
    struct sockaddr_un s={0};char path[sizeof(s.sun_path)+1],host[PATH_MAX];
    if(!addr||len<sizeof(sa_family_t)||len>sizeof(s))return -EACCES;
    int r=read_data(t,&s,addr,len);if(r<0)return r;
    /* 代理模式仅授予出站客户端；否则 listen/accept 可从反向连接绕过代理。 */
    if(s.sun_family!=AF_UNIX)return -EACCES;
    /* 抽象/未命名 Unix socket 不创建文件；文件型 bind 必须执行同一份写策略。 */
    if(!s.sun_path[0])return 0;
    memcpy(path,s.sun_path,sizeof(s.sun_path));path[sizeof(s.sun_path)]=0;
    r=translate_path(t,host,AT_FDCWD,path,true);if(r<0)return r;
    return writable(host);
}
int min_check_syscall(Tracee *t) {
    if(!min_isolation_enabled)return 0;
    Sysnum nr=get_sysnum(t,ORIGINAL);
#define ARG(n) peek_reg(t,ORIGINAL,SYSARG_##n)
#define PATH(n,fd,follow) path_arg(t,SYSARG_##n,(int)(fd),follow)
#define FD(n) fd_arg(t,SYSARG_##n)
    int r;
    switch(nr) {
    case PR_open: return (ARG(2)&(O_ACCMODE|O_CREAT|O_TRUNC|O_APPEND))?PATH(1,AT_FDCWD,!(ARG(2)&O_NOFOLLOW)):0;
    case PR_openat:return (ARG(3)&(O_ACCMODE|O_CREAT|O_TRUNC|O_APPEND))?PATH(2,ARG(1),!(ARG(3)&O_NOFOLLOW)):0;
    case PR_openat2: {
        uint64_t how[3]={0};if(ARG(4)<sizeof(how[0]))return -EINVAL;
        r=read_data(t,how,ARG(3),sizeof(how[0]));if(r<0)return r;
        return (how[0]&(O_ACCMODE|O_CREAT|O_TRUNC|O_APPEND))?PATH(2,ARG(1),!(how[0]&O_NOFOLLOW)):0;
    }
    case PR_creat:case PR_truncate:case PR_truncate64:case PR_chmod:case PR_chown:case PR_chown32:
    case PR_utime:case PR_utimes:case PR_setxattr:case PR_removexattr:return PATH(1,AT_FDCWD,1);
    case PR_unlink:case PR_rmdir:case PR_mkdir:case PR_mknod:case PR_lchown:case PR_lchown32:
    case PR_lsetxattr:case PR_lremovexattr:return PATH(1,AT_FDCWD,0);
    case PR_mkdirat:case PR_mknodat:case PR_unlinkat:return PATH(2,ARG(1),0);
    case PR_fchmodat:case PR_fchownat:case PR_futimesat:return PATH(2,ARG(1),1);
    case PR_utimensat:return ARG(2)?PATH(2,ARG(1),!(ARG(4)&AT_SYMLINK_NOFOLLOW)):FD(1);
    case PR_symlink:return PATH(2,AT_FDCWD,0);
    case PR_symlinkat:return PATH(3,ARG(2),0);
    /* 硬链接修改源 inode 的别名，不能把只读文件带入可写树。 */
    case PR_link:case PR_rename:
        r=PATH(1,AT_FDCWD,0);return r<0?r:PATH(2,AT_FDCWD,0);
    case PR_linkat:case PR_renameat:case PR_renameat2:
        r=PATH(2,ARG(1),nr==PR_linkat&&(ARG(5)&AT_SYMLINK_FOLLOW));return r<0?r:PATH(4,ARG(3),0);
    case PR_write:case PR_writev:case PR_pwrite64:case PR_pwritev:case PR_pwritev2:
    case PR_ftruncate:case PR_ftruncate64:case PR_fchmod:case PR_fchown:case PR_fchown32:
    case PR_fsetxattr:case PR_fremovexattr:case PR_fallocate:return FD(1);
    case PR_sendfile:case PR_sendfile64:return FD(1);
    case PR_splice:case PR_copy_file_range:return FD(3);
    case PR_mmap:case PR_mmap2:
        return ((ARG(3)&PROT_WRITE)&&(ARG(4)&MAP_SHARED)&&!(ARG(4)&MAP_ANONYMOUS))?FD(5):0;
    case PR_connect:return sockaddr_arg(t,ARG(2),ARG(3));
    case PR_bind:return bind_arg(t,ARG(2),ARG(3));
    case PR_sendto:return ARG(5)?sockaddr_arg(t,ARG(5),ARG(6)):0;
    case PR_sendmsg: {
        struct msghdr m; r=read_data(t,&m,ARG(2),sizeof(m));if(r<0)return r;
        /* SCM_RIGHTS 能带入预先打开的越界 fd，因此只允许没有控制消息的普通发送。 */
        if(m.msg_controllen)return -EACCES;
        return m.msg_name?sockaddr_arg(t,(word_t)m.msg_name,m.msg_namelen):0;
    }
    case PR_recvmsg: {
        struct msghdr m;r=read_data(t,&m,ARG(2),sizeof(m));if(r<0)return r;
        return m.msg_controllen?-EACCES:0;
    }
    case PR_recvmmsg:return -EACCES;
    case PR_kill:case PR_tkill:return signal_target(t,(long)ARG(1));
    case PR_tgkill:r=signal_target(t,(long)ARG(1));return r<0?r:signal_target(t,(long)ARG(2));
    case PR_rt_sigqueueinfo:return signal_target(t,(long)ARG(1));
    case PR_rt_tgsigqueueinfo:r=signal_target(t,(long)ARG(1));return r<0?r:signal_target(t,(long)ARG(2));
    default:return 0;
    }
#undef FD
#undef PATH
#undef ARG
}
int min_guest_setup(void) {
    if(!min_isolation_enabled)return 0;
    if(!root_count){errno=EINVAL;return -1;}
    /* fd 是路径策略的输入，不能把宿主已打开的文件或网络连接直接继承给 guest。 */
    DIR *fds=opendir("/proc/self/fd");if(!fds)return -1;
    struct dirent *entry;
    while((entry=readdir(fds))) {
        char *end;long fd=strtol(entry->d_name,&end,10);
        if(end!=entry->d_name&&!*end&&fd>=3&&fd!=dirfd(fds))close((int)fd);
    }
    closedir(fds);
    return min_install_filter(min_offline);
}

int min_trap_denied(Tracee *t) {
    if(!min_isolation_enabled)return 0;
    struct seccomp_data data={.arch=MIN_NATIVE_ARCH,.nr=(int)peek_reg(t,CURRENT,SYSARG_NUM)};
    if(get_abi(t)!=ABI_DEFAULT)return -EPERM;
    for(int i=0;i<6;i++)data.args[i]=peek_reg(t,CURRENT,(Reg)(SYSARG_1+i));
    uint32_t action=min_filter_action(&data,min_offline);
    return (action&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ERRNO?-(int)(action&SECCOMP_RET_DATA):0;
}
