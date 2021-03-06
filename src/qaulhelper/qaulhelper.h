/*
 * qaul.net is free software
 * licensed under GPL (version 3)
 */

/**
 * qaul helper functions running as root user
 *
 * For this executable the suid bit needs to be set, to be
 * callable from userspace. This is done by the qaul.net installer.
 * The path of the installed qaulhelper binary is:
 *   Linux: /usr/local/lib/qaul/bin/qaulhelper
 *   OSX:   /Library/qaul.net/bin/qaulhelper
 *
 * To set the suid bit manually for testing, open a Terminal, navigate
 * to the qaulhelper executable binary and type:
 *   sudo chown root qaulhelper
 *   sudo chgrp root qaulhelper
 *   sudo chmod 6755 qaulhelper
 *
 *
 * for usage documentation see distribution file:
 *   Linux: linux.c
 *   OS:    osx.c
 */

#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <QaulConfig.h>


/**
 * start olsrd
 */
int start_olsrd (int argc, const char * argv[]);

/**
 * stop olsrd
 */
int stop_olsrd (int argc, const char * argv[]);

/**
 * start port forwarding
 */
int start_portforwarding (int argc, const char * argv[]);

/**
 * stop port forwarding
 */
int stop_portforwarding (int argc, const char * argv[]);


#ifdef QAUL_PORT_OSX

/**
 * enable wifi
 */
int enable_wifi (int argc, const char * argv[]);

/**
 * disable wifi
 */
int disable_wifi (int argc, const char * argv[]);

#endif // QAUL_PORT_OSX


#ifdef QAUL_PORT_LINUX

/**
 * configure wifi, create or join IBSS
 */
int configure_wifi (int argc, const char * argv[]);

#endif // QAUL_PORT_LINUX


/**
 * configure static IP
 */
int set_ip (int argc, const char * argv[]);

/**
 * set dns servers
 */
int set_dns (int argc, const char * argv[]);


#ifdef QAUL_PORT_LINUX

/**
 * remove dns servers
 */
int remove_dns (int argc, const char * argv[]);

#endif // QAUL_PORT_LINUX


/**
 * start gateway
 *
 * this function needs to be called as root
 */
int start_gateway (int argc, const char * argv[]);

/**
 * stop gateway
 */
int stop_gateway (int argc, const char * argv[]);


#ifdef QAUL_PORT_OSX

/**
 * create network profile
 */
int create_networkprofile (int argc, const char * argv[]);

/**
 * switch network profile
 */
int switch_networkprofile (int argc, const char * argv[]);

/**
 * delete network profile
 */
int delete_networkprofile (int argc, const char * argv[]);

/**
 * create or join IBSS
 *
 * only for OSX <= 10.5
 */
int create_ibss (int argc, const char * argv[]);

/**
 * configure DHCP
 */
int set_dhcp (int argc, const char * argv[]);

#endif // QAUL_PORT_OSX


/**
 * validate IP argument
 */
int validate_ip (const char* str);

/**
 * validate interface argument
 */
int validate_interface (const char* str);

/**
 * validate service argument
 */
int validate_service (const char* str);

/**
 * validate essid argument
 */
int validate_essid (const char* str);

/**
 * validate service argument
 */
int validate_bssid (const char* str);

/**
 * validate number argument
 */
int validate_number (const char* str);

/**
 * validate path argument
 */
int validate_path (const char* str);

/**
 * check if the char is a number
 */
int validate_char_number (char mychar);

/**
 * check if the char is an ascii letter
 */
int validate_char_letter (char mychar);

/**
 * check char for problematic entities
 */
int validate_char_problematic (char mychar);
