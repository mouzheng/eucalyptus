/*
Copyright (c) 2009  Eucalyptus Systems, Inc.	

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by 
the Free Software Foundation, only version 3 of the License.  
 
This file is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
for more details.  

You should have received a copy of the GNU General Public License along
with this program.  If not, see <http://www.gnu.org/licenses/>.
 
Please contact Eucalyptus Systems, Inc., 130 Castilian
Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/> 
if you need additional information or have any questions.

This file may incorporate work covered under the following copyright and
permission notice:

  Software License Agreement (BSD License)

  Copyright (c) 2008, Regents of the University of California
  

  Redistribution and use of this software in source and binary forms, with
  or without modification, are permitted provided that the following
  conditions are met:

    Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

    Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
  PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
  OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
  THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
  LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
  SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
  IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
  BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
  THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
  OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
  WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
  ANY SUCH LICENSES OR RIGHTS.
*/
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#define M1 "aa:bb:00:00:00:00"
#define I1 "127.0.0.0"
#define M2 "aa:bb:00:00:00:01"
#define I2 "127.0.0.1"
#define M3 "aa:bb:00:00:00:02"
#define I3 "127.0.0.2"
#define M4 "aa:bb:00:00:00:03"
#define I4 "127.0.0.3"

#include "dhcp.h"

int main (int argc, char ** argv)
{
    int err;

    err=dhcp_add_dev(NULL, NULL); assert(err!=0);
    err=dhcp_add_dev("eucadev3", "192.168.3.1"); assert(err==0);
    err=dhcp_add_dev("eucadev4", "192.168.4.1"); assert(err==0);
    err=dhcp_add_entry(NULL, NULL); assert(err!=0);
    err=dhcp_add_entry(M1, I1); assert(err==0);

/*
    err=dhcp_del_entry(M1); assert(err==0);
    err=dhcp_add_entry(M2, I2);
    err=dhcp_add_entry(M3, I3); 
    err=dhcp_add_entry(M4, I4); assert(err==0);
    err=dhcp_add_entry(M4, I4); assert(err!=0);
    err=dhcp_add_entry(M2, I2); assert(err!=0);
    err=dhcp_add_entry(M3, I3); assert(err!=0);
    err=dhcp_del_entry(M1); assert(err!=0);
    err=dhcp_del_entry(M2); assert(err==0);
    err=dhcp_del_entry(M4); assert(err==0);
    err=dhcp_del_entry(M3); assert(err==0);
    err=dhcp_add_entry(M1, I1); assert(err==0);
    err=dhcp_del_entry(M1); assert(err==0);
*/

    printf("all tests passed\n");
    
    return 0;
}
