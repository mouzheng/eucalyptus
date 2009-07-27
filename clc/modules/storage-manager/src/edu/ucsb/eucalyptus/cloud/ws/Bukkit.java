/*
 * Author: Sunil Soman sunils@cs.ucsb.edu
 */

package edu.ucsb.eucalyptus.cloud.ws;

import edu.ucsb.eucalyptus.cloud.*;
import edu.ucsb.eucalyptus.cloud.entities.*;
import edu.ucsb.eucalyptus.keys.AbstractKeyStore;
import edu.ucsb.eucalyptus.keys.Hashes;
import edu.ucsb.eucalyptus.keys.UserKeyStore;
import edu.ucsb.eucalyptus.msgs.*;
import edu.ucsb.eucalyptus.storage.StorageManager;
import edu.ucsb.eucalyptus.storage.fs.FileSystemStorageManager;
import edu.ucsb.eucalyptus.transport.query.WalrusQueryDispatcher;
import edu.ucsb.eucalyptus.util.*;
import org.apache.log4j.Logger;
import org.apache.tools.ant.util.DateUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, only version 3 of the License.
 *
 *
 * This file is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Please contact Eucalyptus Systems, Inc., 130 Castilian
 * Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 * if you need additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright and
 * permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms, with
 *   or without modification, are permitted provided that the following
 *   conditions are met:
 *
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *   IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *   TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *   PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *   OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *   THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *   LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *   SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *   BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *   THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *   OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *   WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *   ANY SUCH LICENSES OR RIGHTS.
 ******************************************************************************/

public class Bukkit {

    private static Logger LOG = Logger.getLogger( Bukkit.class );

    private static WalrusDataMessenger imageMessenger = new WalrusDataMessenger();

    static StorageManager storageManager;
    static boolean shouldEnforceUsageLimits = true;
    private static boolean enableSnapshots = false;

    private static boolean sharedMode = false;
    private final long CACHE_PROGRESS_TIMEOUT = 600000L; //ten minutes
    private long CACHE_RETRY_TIMEOUT = 1000L;
    private final int CACHE_RETRY_LIMIT = 3;
    private static ConcurrentHashMap<String, ImageCacher> imageCachers = new ConcurrentHashMap<String, ImageCacher>();

    static {
        storageManager = new FileSystemStorageManager(WalrusProperties.bucketRootDirectory);
        String limits = System.getProperty(WalrusProperties.USAGE_LIMITS_PROPERTY);
        if(limits != null) {
            shouldEnforceUsageLimits = Boolean.parseBoolean(limits);
        }
        initializeForEBS();
        cleanFailedCachedImages();
    }

    public static void initializeForEBS() {
        enableSnapshots = true;
        storageManager.initialize();
        startupChecks();
        try {
            storageManager.checkPreconditions();
        } catch(Exception ex) {
            enableSnapshots = false;
        }
        //inform SC in case it is running on the same host
        sharedMode = true;
    }

    public static boolean getSharedMode() {
        return sharedMode;
    }

    public static void startupChecks() {
        cleanFailedSnapshots();
    }

    private static void cleanFailedSnapshots() {
        EntityWrapper<WalrusSnapshotInfo> db = new EntityWrapper<WalrusSnapshotInfo>();
        WalrusSnapshotInfo snapshotInfo = new WalrusSnapshotInfo();
        snapshotInfo.setTransferred(false);
        List<WalrusSnapshotInfo> snapshotInfos = db.query(snapshotInfo);
        for(WalrusSnapshotInfo snapInfo : snapshotInfos) {
            String snapSetId = snapInfo.getSnapshotSetId();
            EntityWrapper<WalrusSnapshotSet> dbSet = db.recast(WalrusSnapshotSet.class);
            List<WalrusSnapshotSet> snapsets = dbSet.query(new WalrusSnapshotSet(snapSetId));
            if(snapsets.size() > 0) {
                WalrusSnapshotSet snapset = snapsets.get(0);
                List<WalrusSnapshotInfo>snapList = snapset.getSnapshotSet();
                if(snapList.contains(snapInfo))
                    snapList.remove(snapInfo);
            }
            db.delete(snapInfo);
            try {
                storageManager.deleteObject(snapInfo.getSnapshotSetId(), snapInfo.getSnapshotId());
            } catch(Exception ex) {
                LOG.error(ex);
            }
        }
        db.commit();
    }

    private static void cleanFailedCachedImages() {
        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo();
        searchImageCacheInfo.setInCache(false);
        List<ImageCacheInfo> icInfos = db.query(searchImageCacheInfo);
        for(ImageCacheInfo icInfo : icInfos) {
            String decryptedImageName = icInfo.getImageName();
            String bucket = icInfo.getBucketName();
            LOG.info("Cleaning failed cache entry: " + bucket + "/" + icInfo.getManifestName());
            try {
                if(decryptedImageName.contains(".tgz")) {
                    storageManager.deleteObject(bucket, decryptedImageName.replaceAll(".tgz", "crypt.gz"));
                    storageManager.deleteObject(bucket, decryptedImageName.replaceAll(".tgz", ".tar"));
                    storageManager.deleteObject(bucket, decryptedImageName.replaceAll(".tgz", ""));
                }
                storageManager.deleteObject(bucket, decryptedImageName);
            } catch(IOException ex) {
                LOG.error(ex);
            }
            db.delete(icInfo);
        }
        db.commit();
    }

    public Bukkit () {}

    public InitializeWalrusResponseType InitializeWalrus(InitializeWalrusType request) {
        InitializeWalrusResponseType reply = (InitializeWalrusResponseType) request.getReply();
        initializeForEBS();
        return reply;
    }

    public CreateBucketResponseType CreateBucket(CreateBucketType request) throws EucalyptusCloudException {
        CreateBucketResponseType reply = (CreateBucketResponseType) request.getReply();
        String userId = request.getUserId();

        String bucketName = request.getBucket();

        if(userId == null) {
            throw new AccessDeniedException(bucketName);
        }

        AccessControlListType accessControlList = request.getAccessControlList();
        if (accessControlList == null) {
            accessControlList = new AccessControlListType();
        }

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();

        if(shouldEnforceUsageLimits && !request.isAdministrator()) {
            BucketInfo searchBucket = new BucketInfo();
            searchBucket.setOwnerId(userId);
            List<BucketInfo> bucketList = db.query(searchBucket);
            if(bucketList.size() >= WalrusProperties.MAX_BUCKETS_PER_USER) {
                db.rollback();
                throw new TooManyBucketsException(bucketName);
            }
        }

        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if(bucketList.size() > 0) {
            if(bucketList.get(0).getOwnerId().equals(userId)) {
                //bucket already exists and you created it
                db.rollback();
                throw new BucketAlreadyOwnedByYouException(bucketName);
            }
            //bucket already exists
            db.rollback();
            throw new BucketAlreadyExistsException(bucketName);
        }   else {
            //create bucket and set its acl
            BucketInfo bucket = new BucketInfo(userId, bucketName, new Date());
            ArrayList<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
            bucket.addGrants(userId, grantInfos, accessControlList);
            bucket.setGrants(grantInfos);
            bucket.setBucketSize(0L);

            ArrayList<ObjectInfo> objectInfos = new ArrayList<ObjectInfo>();
            bucket.setObjects(objectInfos);
            //call the storage manager to save the bucket to disk
            try {
                storageManager.createBucket(bucketName);
                db.add(bucket);
            } catch (IOException ex) {
                LOG.error(ex);
                db.rollback();
                throw new EucalyptusCloudException(bucketName);
            }
        }
        db.commit();

        reply.setBucket(bucketName);
        return reply;
    }

    public DeleteBucketResponseType DeleteBucket(DeleteBucketType request) throws EucalyptusCloudException {
        DeleteBucketResponseType reply = (DeleteBucketResponseType) request.getReply();
        String bucketName = request.getBucket();
        String userId = request.getUserId();
        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo searchBucket = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(searchBucket);


        if(bucketList.size() > 0) {
            BucketInfo bucketFound = bucketList.get(0);
            if (bucketFound.canWrite(userId)) {
                List<ObjectInfo> objectInfos = bucketFound.getObjects();
                if(objectInfos.size() == 0) {
                    //asychronously flush any images in this bucket
                    EntityWrapper<ImageCacheInfo> dbIC = db.recast(ImageCacheInfo.class);
                    ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo();
                    searchImageCacheInfo.setBucketName(bucketName);
                    List<ImageCacheInfo> foundImageCacheInfos = dbIC.query(searchImageCacheInfo);

                    if(foundImageCacheInfos.size() > 0) {
                        ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
                        ImageCacheFlusher imageCacheFlusher = new ImageCacheFlusher(bucketName, foundImageCacheInfo.getManifestName());
                        imageCacheFlusher.start();
                    }

                    db.delete(bucketFound);
                    //Actually remove the bucket from the backing store
                    try {
                        storageManager.deleteBucket(bucketName);
                    } catch (IOException ex) {
                        //set exception code in reply
                        LOG.error(ex);
                    }
                    Status status = new Status();
                    status.setCode(204);
                    status.setDescription("No Content");
                    reply.setStatus(status);
                } else {
                    db.rollback();
                    throw new BucketNotEmptyException(bucketName);
                }
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public ListAllMyBucketsResponseType ListAllMyBuckets(ListAllMyBucketsType request) throws EucalyptusCloudException {
        ListAllMyBucketsResponseType reply = (ListAllMyBucketsResponseType) request.getReply();
        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        String userId = request.getUserId();

        if(userId == null) {
            throw new AccessDeniedException("no such user");
        }

        EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
        UserInfo searchUser = new UserInfo(userId);
        List<UserInfo> userInfoList = db2.query(searchUser);

        if(userInfoList.size() > 0) {
            UserInfo user = userInfoList.get(0);
            BucketInfo searchBucket = new BucketInfo();
            searchBucket.setOwnerId(userId);
            List<BucketInfo> bucketInfoList = db.query(searchBucket);

            ArrayList<BucketListEntry> buckets = new ArrayList<BucketListEntry>();

            for(BucketInfo bucketInfo: bucketInfoList) {
                buckets.add(new BucketListEntry(bucketInfo.getBucketName(), DateUtils.format(bucketInfo.getCreationDate().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z"));
            }

            CanonicalUserType owner = new CanonicalUserType(user.getQueryId(), user.getUserName());
            ListAllMyBucketsList bucketList = new ListAllMyBucketsList();
            reply.setOwner(owner);
            bucketList.setBuckets(buckets);
            reply.setBucketList(bucketList);
        } else {
            db.rollback();
            throw new AccessDeniedException(userId);
        }
        db.commit();
        return reply;
    }

    public GetBucketAccessControlPolicyResponseType GetBucketAccessControlPolicy(GetBucketAccessControlPolicyType request) throws EucalyptusCloudException
    {
        GetBucketAccessControlPolicyResponseType reply = (GetBucketAccessControlPolicyResponseType) request.getReply();

        String bucketName = request.getBucket();
        String userId = request.getUserId();
        String ownerId = null;

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        AccessControlListType accessControlList = new AccessControlListType();

        if (bucketList.size() > 0) {
            //construct access control policy from grant infos
            BucketInfo bucket = bucketList.get(0);
            List<GrantInfo> grantInfos = bucket.getGrants();
            if (bucket.canReadACP(userId)) {
                ownerId = bucket.getOwnerId();
                ArrayList<Grant> grants = new ArrayList<Grant>();
                for (GrantInfo grantInfo: grantInfos) {
                    String uId = grantInfo.getUserId();
                    UserInfo userInfo = new UserInfo(uId);
                    EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
                    List<UserInfo> grantUserInfos = db2.query(userInfo);
                    db2.commit();

                    if(grantUserInfos.size() > 0) {
                        UserInfo grantUserInfo = grantUserInfos.get(0);
                        bucket.readPermissions(grants);
                        addPermission(grants, grantUserInfo, grantInfo);
                    }
                }
                accessControlList.setGrants(grants);
            }
        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        UserInfo userInfo = new UserInfo(ownerId);
        EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
        List<UserInfo> ownerUserInfos = db2.query(userInfo);
        db2.commit();

        AccessControlPolicyType accessControlPolicy = new AccessControlPolicyType();
        if(ownerUserInfos.size() > 0) {
            UserInfo ownerUserInfo = ownerUserInfos.get(0);
            accessControlPolicy.setOwner(new CanonicalUserType(ownerUserInfo.getQueryId(), ownerUserInfo.getUserName()));
            accessControlPolicy.setAccessControlList(accessControlList);
        }
        reply.setAccessControlPolicy(accessControlPolicy);
        db.commit();
        return reply;
    }


    private static void addPermission(ArrayList<Grant>grants, UserInfo userInfo, GrantInfo grantInfo) {
        CanonicalUserType user = new CanonicalUserType(userInfo.getQueryId(), userInfo.getUserName());

        if (grantInfo.isRead() && grantInfo.isWrite() && grantInfo.isReadACP() && grantInfo.isWriteACP()) {
            grants.add(new Grant(new Grantee(user), "FULL_CONTROL"));
            return;
        }

        if (grantInfo.isRead()) {
            grants.add(new Grant(new Grantee(user), "READ"));
        }

        if (grantInfo.isWrite()) {
            grants.add(new Grant(new Grantee(user), "WRITE"));
        }

        if (grantInfo.isReadACP()) {
            grants.add(new Grant(new Grantee(user), "READ_ACP"));
        }

        if (grantInfo.isWriteACP()) {
            grants.add(new Grant(new Grantee(user), "WRITE_ACP"));
        }
    }

    public PutObjectResponseType PutObject (PutObjectType request) throws EucalyptusCloudException {
        PutObjectResponseType reply = (PutObjectResponseType) request.getReply();
        String userId = request.getUserId();

        String bucketName = request.getBucket();
        String objectKey = request.getKey();

        Long oldBucketSize = 0L;

        String md5 = "";
        Date lastModified = null;

        AccessControlListType accessControlList = request.getAccessControlList();
        if (accessControlList == null) {
            accessControlList = new AccessControlListType();
        }

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if(bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if (bucket.canWrite(userId)) {

                ObjectInfo foundObject = null;
                List<ObjectInfo> objectInfos = bucket.getObjects();
                for (ObjectInfo objectInfo: objectInfos) {
                    if (objectInfo.getObjectKey().equals(objectKey)) {
                        //key (object) exists. check perms
                        if (!objectInfo.canWrite(userId)) {
                            db.rollback();
                            throw new AccessDeniedException(objectKey);
                        }
                        foundObject = objectInfo;
                        oldBucketSize = -foundObject.getSize();
                        break;
                    }
                }
                //write object to bucket
                String objectName;
                if (foundObject == null) {
                    //not found. create an object info
                    foundObject = new ObjectInfo(objectKey);
                    List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                    foundObject.addGrants(userId, grantInfos, accessControlList);
                    foundObject.setGrants(grantInfos);
                    objectName = objectKey.replaceAll("/", "-") + Hashes.getRandom(4);
                    foundObject.setObjectName(objectName);
                    objectInfos.add(foundObject);
                } else {
                    //object already exists. see if we can modify acl
                    if (foundObject.canWriteACP(userId)) {
                        List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                        foundObject.addGrants(userId, grantInfos, accessControlList);
                        foundObject.setGrants(grantInfos);
                    }
                    objectName = foundObject.getObjectName();
                }
                foundObject.setObjectKey(objectKey);
                foundObject.setOwnerId(userId);
                foundObject.replaceMetaData(request.getMetaData());
                //writes are unconditional
                String randomKey = request.getRandomKey();

                WalrusDataMessenger messenger = WalrusQueryDispatcher.getWriteMessenger();
                String key = bucketName + "." + objectKey;
                LinkedBlockingQueue<WalrusDataMessage> putQueue = messenger.getQueue(key, randomKey);

                try {
                    WalrusDataMessage dataMessage;
                    String tempObjectName = objectName;
                    MessageDigest digest = null;
                    long size = 0;
                    while ((dataMessage = putQueue.take())!=null) {
                        if(WalrusDataMessage.isStart(dataMessage)) {
                            tempObjectName = objectName + "." + Hashes.getRandom(12);
                            digest = Hashes.Digest.MD5.get();
                        } else if(WalrusDataMessage.isEOF(dataMessage)) {
                            //commit object
                            try {
                                storageManager.renameObject(bucketName, tempObjectName, objectName);
                            } catch (IOException ex) {
                                LOG.error(ex);
                                db.rollback();
                                throw new EucalyptusCloudException(objectKey);
                            }
                            md5 = bytesToHex(digest.digest());
                            lastModified = new Date();
                            foundObject.setEtag(md5);
                            foundObject.setSize(size);
                            foundObject.setLastModified(lastModified);
                            foundObject.setStorageClass("STANDARD");
                            reply.setSize(size);
                            if(shouldEnforceUsageLimits && !request.isAdministrator()) {
                                Long bucketSize = bucket.getBucketSize();
                                long newSize = bucketSize + oldBucketSize + size;
                                if(newSize > WalrusProperties.MAX_BUCKET_SIZE) {
                                    db.rollback();
                                    throw new EntityTooLargeException(objectKey);
                                }
                                bucket.setBucketSize(newSize);
                            }
                            db.commit();
                            //restart all interrupted puts
                            WalrusMonitor monitor = messenger.getMonitor(key);
                            synchronized (monitor) {
                                monitor.setLastModified(lastModified);
                                monitor.setMd5(md5);
                                monitor.notifyAll();
                            }
                            messenger.removeQueue(key, randomKey);
                            messenger.removeMonitor(key);
                            LOG.info("Transfer complete: " + key);
                            break;

                        } else if(WalrusDataMessage.isInterrupted(dataMessage)) {

                            //there was a write after this one started
                            //abort writing but wait until the other (last) writer has completed
                            WalrusMonitor monitor = messenger.getMonitor(key);
                            synchronized (monitor) {
                                monitor.wait();
                                lastModified = monitor.getLastModified();
                                md5 = monitor.getMd5();
                            }
                            //ok we are done here
                            try {
                                storageManager.deleteObject(bucketName, tempObjectName);
                            } catch (IOException ex) {
                                LOG.error(ex);
                            }
                            db.rollback();
                            LOG.info("Transfer interrupted: "+ key);
                            break;
                        } else {
                            assert(WalrusDataMessage.isData(dataMessage));
                            byte[] data = dataMessage.getPayload();
                            //start writing object (but do not committ yet)
                            try {
                                storageManager.putObject(bucketName, tempObjectName, data, true);
                            } catch (IOException ex) {
                                LOG.error(ex);
                            }
                            //calculate md5 on the fly
                            size += data.length;
                            digest.update(data);
                        }
                    }
                } catch (InterruptedException ex) {
                    LOG.error(ex, ex);
                    throw new EucalyptusCloudException();
                }
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }
        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        reply.setEtag(md5);
        reply.setLastModified(DateUtils.format(lastModified.getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
        return reply;
    }

    public PostObjectResponseType PostObject (PostObjectType request) throws EucalyptusCloudException {
        PostObjectResponseType reply = (PostObjectResponseType) request.getReply();

        String bucketName = request.getBucket();
        String key = request.getKey();

        PutObjectType putObject = new PutObjectType();
        putObject.setUserId(request.getUserId());
        putObject.setBucket(bucketName);
        putObject.setKey(key);
        putObject.setRandomKey(request.getRandomKey());
        putObject.setAccessControlList(request.getAccessControlList());
        putObject.setContentLength(request.getContentLength());
        putObject.setAccessKeyID(request.getAccessKeyID());
        putObject.setEffectiveUserId(request.getEffectiveUserId());
        putObject.setCredential(request.getCredential());
        putObject.setIsCompressed(request.getIsCompressed());
        putObject.setMetaData(request.getMetaData());
        putObject.setStorageClass(request.getStorageClass());

        PutObjectResponseType putObjectResponse = PutObject(putObject);

        String etag = putObjectResponse.getEtag();
        reply.setEtag(etag);
        reply.setLastModified(putObjectResponse.getLastModified());
        reply.set_return(putObjectResponse.get_return());
        reply.setMetaData(putObjectResponse.getMetaData());
        reply.setErrorCode(putObjectResponse.getErrorCode());
        reply.setSize(putObjectResponse.getSize());
        reply.setStatusMessage(putObjectResponse.getStatusMessage());

        String successActionRedirect = request.getSuccessActionRedirect();
        if(successActionRedirect != null) {
            try {
                java.net.URI addrUri = new URL(successActionRedirect).toURI();
                InetAddress.getByName(addrUri.getHost());
            } catch(Exception ex) {
                LOG.warn(ex);
            }
            String paramString = "bucket=" + bucketName + "&key=" + key + "&etag=quot;" + etag + "quot;";
            reply.setRedirectUrl(successActionRedirect + "?" + paramString);
        } else {
            Integer successActionStatus = request.getSuccessActionStatus();
            if(successActionStatus != null) {
                if((successActionStatus == 200) || (successActionStatus == 201)) {
                    reply.setSuccessCode(successActionStatus);
                    if(successActionStatus == 200) {
                        return reply;
                    } else {
                        reply.setBucket(bucketName);
                        reply.setKey(key);
                        reply.setLocation(WalrusProperties.WALRUS_URL + "/" + bucketName + "/" + key);
                    }
                } else {
                    reply.setSuccessCode(204);
                    return reply;
                }
            }
        }
        return reply;
    }

    public PutObjectInlineResponseType PutObjectInline (PutObjectInlineType request) throws EucalyptusCloudException {
        PutObjectInlineResponseType reply = (PutObjectInlineResponseType) request.getReply();
        String userId = request.getUserId();

        String bucketName = request.getBucket();
        String objectKey = request.getKey();

        String md5 = "";
        Long oldBucketSize = 0L;
        Date lastModified;

        AccessControlListType accessControlList = request.getAccessControlList();
        if (accessControlList == null) {
            accessControlList = new AccessControlListType();
        }

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if(bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if (bucket.canWrite(userId)) {
                ObjectInfo foundObject = null;
                List<ObjectInfo> objectInfos = bucket.getObjects();
                for (ObjectInfo objectInfo: objectInfos) {
                    if (objectInfo.getObjectKey().equals(objectKey)) {
                        //key (object) exists. check perms
                        if (!objectInfo.canWrite(userId)) {
                            db.rollback();
                            throw new AccessDeniedException(objectKey);
                        }
                        foundObject = objectInfo;
                        oldBucketSize = -foundObject.getSize();
                        break;
                    }
                }
                //write object to bucket
                String objectName;
                if (foundObject == null) {
                    //not found. create an object info
                    foundObject = new ObjectInfo(objectKey);
                    List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                    foundObject.addGrants(userId, grantInfos, accessControlList);
                    foundObject.setGrants(grantInfos);
                    objectName = objectKey.replaceAll("/", "-") + Hashes.getRandom(4);
                    foundObject.setObjectName(objectName);
                    objectInfos.add(foundObject);
                } else {
                    //object already exists. see if we can modify acl
                    if (foundObject.canWriteACP(userId)) {
                        List<GrantInfo> grantInfos = foundObject.getGrants();
                        foundObject.addGrants(userId, grantInfos, accessControlList);
                    }
                    objectName = foundObject.getObjectName();
                }
                foundObject.setObjectKey(objectKey);
                foundObject.setOwnerId(userId);
                try {
                    //writes are unconditional
                    byte[] base64Data = request.getBase64Data().getBytes();
                    foundObject.setObjectName(objectName);
                    storageManager.putObject(bucketName, objectName, base64Data, false);
                    md5 = Hashes.getHexString(Hashes.Digest.MD5.get().digest(base64Data));
                    foundObject.setEtag(md5);
                    Long size = Long.parseLong(request.getContentLength());
                    foundObject.setSize(size);
                    if(shouldEnforceUsageLimits && !request.isAdministrator()) {
                        Long bucketSize = bucket.getBucketSize();
                        long newSize = bucketSize + oldBucketSize + size;
                        if(newSize > WalrusProperties.MAX_BUCKET_SIZE) {
                            db.rollback();
                            throw new EntityTooLargeException(objectKey);
                        }
                        bucket.setBucketSize(newSize);
                    }
                    //Add meta data if specified
                    foundObject.replaceMetaData(request.getMetaData());

                    //TODO: add support for other storage classes
                    foundObject.setStorageClass("STANDARD");
                    lastModified = new Date();
                    foundObject.setLastModified(lastModified);
                } catch (IOException ex) {
                    LOG.error(ex);
                    db.rollback();
                    throw new EucalyptusCloudException(bucketName);
                }
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }

        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }

        db.commit();

        reply.setEtag(md5);
        reply.setLastModified(DateUtils.format(lastModified.getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
        return reply;
    }

    private void AddObject (String userId, String bucketName, String key) throws EucalyptusCloudException {

        AccessControlListType accessControlList = new AccessControlListType();
        if (accessControlList == null) {
            accessControlList = new AccessControlListType();
        }

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if(bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if (bucket.canWrite(userId)) {
                List<ObjectInfo> objectInfos = bucket.getObjects();
                for (ObjectInfo objectInfo: objectInfos) {
                    if (objectInfo.getObjectKey().equals(key)) {
                        //key (object) exists.
                        db.rollback();
                        throw new EucalyptusCloudException("object already exists " + key);
                    }
                }
                //write object to bucket
                ObjectInfo objectInfo = new ObjectInfo(key);
                List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                objectInfo.addGrants(userId, grantInfos, accessControlList);
                objectInfo.setGrants(grantInfos);
                objectInfos.add(objectInfo);

                objectInfo.setObjectKey(key);
                objectInfo.setOwnerId(userId);
                objectInfo.setSize(storageManager.getSize(bucketName, key));
                objectInfo.setEtag("");
                objectInfo.setLastModified(new Date());
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }
        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
    }

    public DeleteObjectResponseType DeleteObject (DeleteObjectType request) throws EucalyptusCloudException {
        DeleteObjectResponseType reply = (DeleteObjectResponseType) request.getReply();
        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfos = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfos);

        if (bucketList.size() > 0) {
            BucketInfo bucketInfo = bucketList.get(0);
            ObjectInfo foundObject = null;

            for (ObjectInfo objectInfo: bucketInfo.getObjects()) {
                if (objectInfo.getObjectKey().equals(objectKey)) {
                    foundObject = objectInfo;
                }
            }

            if (foundObject != null) {
                if (foundObject.canWrite(userId)) {
                    bucketInfo.getObjects().remove(foundObject);
                    String objectName = foundObject.getObjectName();
                    for (GrantInfo grantInfo: foundObject.getGrants()) {
                        db.getEntityManager().remove(grantInfo);
                    }
                    Long size = foundObject.getSize();
                    bucketInfo.setBucketSize(bucketInfo.getBucketSize() - size);
                    db.getEntityManager().remove(foundObject);
                    try {
                        storageManager.deleteObject(bucketName, objectName);
                    } catch (IOException ex) {
                        db.rollback();
                        LOG.error(ex);
                        throw new EucalyptusCloudException(objectKey);
                    }
                    reply.setCode("200");
                    reply.setDescription("OK");
                } else {
                    db.rollback();
                    throw new AccessDeniedException(objectKey);
                }
            } else {
                db.rollback();
                throw new NoSuchEntityException(objectKey);
            }
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public ListBucketResponseType ListBucket(ListBucketType request) throws EucalyptusCloudException {
        ListBucketResponseType reply = (ListBucketResponseType) request.getReply();
        String bucketName = request.getBucket();
        String userId = request.getUserId();
        String prefix = request.getPrefix();
        if(prefix == null)
            prefix = "";

        String marker = request.getMarker();
        if(marker == null)
            marker = "";

        int maxKeys = -1;
        String maxKeysString = request.getMaxKeys();
        if(maxKeysString != null)
            maxKeys = Integer.parseInt(maxKeysString);
        else
            maxKeys = WalrusProperties.MAX_KEYS;

        String delimiter = request.getDelimiter();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        ArrayList<PrefixEntry> prefixes = new ArrayList<PrefixEntry>();

        if(bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if(bucket.canRead(userId)) {
                reply.setName(bucketName);
                reply.setIsTruncated(false);
                if(maxKeys >= 0)
                    reply.setMaxKeys(maxKeys);
                reply.setPrefix(prefix);
                reply.setMarker(marker);
                if(delimiter != null)
                    reply.setDelimiter(delimiter);
                List<ObjectInfo> objectInfos = bucket.getObjects();
                if(objectInfos.size() > 0) {
                    int howManyProcessed = 0;
                    ArrayList<ListEntry> contents = new ArrayList<ListEntry>();
                    for(ObjectInfo objectInfo: objectInfos) {
                        String objectKey = objectInfo.getObjectKey();
                        if(marker != null) {
                            if(objectKey.compareTo(marker) < 0)
                                continue;
                        }
                        if(prefix != null) {
                            if(!objectKey.startsWith(prefix)) {
                                continue;
                            } else {
                                if(delimiter != null) {
                                    String[] parts = objectKey.substring(prefix.length()).split(delimiter);
                                    if(parts.length > 1) {
                                        String prefixString = parts[0] + delimiter;
                                        boolean foundPrefix = false;
                                        for(PrefixEntry prefixEntry : prefixes) {
                                            if(prefixEntry.getPrefix().equals(prefixString)) {
                                                foundPrefix = true;
                                                break;
                                            }
                                        }
                                        if(!foundPrefix) {
                                            prefixes.add(new PrefixEntry(prefixString));
                                            if(maxKeys >= 0) {
                                                if(howManyProcessed++ > maxKeys) {
                                                    reply.setIsTruncated(true);
                                                    break;
                                                }
                                            }
                                        }
                                        continue;
                                    }
                                }
                            }
                        }
                        if(maxKeys >= 0) {
                            if(howManyProcessed++ > maxKeys) {
                                reply.setIsTruncated(true);
                                break;
                            }
                        }
                        ListEntry listEntry = new ListEntry();
                        listEntry.setKey(objectKey);
                        listEntry.setEtag(objectInfo.getEtag());
                        listEntry.setLastModified(DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
                        listEntry.setStorageClass(objectInfo.getStorageClass());
                        String displayName = objectInfo.getOwnerId();

                        EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
                        UserInfo userInfo = new UserInfo(displayName);
                        List<UserInfo> ownerInfos = db2.query(userInfo);
                        db2.commit();
                        if(ownerInfos.size() > 0) {
                            listEntry.setOwner(new CanonicalUserType(ownerInfos.get(0).getQueryId(), displayName));
                        }
                        ArrayList<MetaDataEntry> metaData = new ArrayList<MetaDataEntry>();
                        objectInfo.returnMetaData(metaData);
                        reply.setMetaData(metaData);
                        listEntry.setSize(objectInfo.getSize());
                        listEntry.setStorageClass(objectInfo.getStorageClass());
                        contents.add(listEntry);
                    }
                    reply.setContents(contents);
                    if(prefix != null) {
                        reply.setCommonPrefixes(prefixes);
                    }
                }
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public GetObjectAccessControlPolicyResponseType GetObjectAccessControlPolicy(GetObjectAccessControlPolicyType request) throws EucalyptusCloudException
    {
        GetObjectAccessControlPolicyResponseType reply = (GetObjectAccessControlPolicyResponseType) request.getReply();

        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();
        String ownerId = null;

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        AccessControlListType accessControlList = new AccessControlListType();

        if (bucketList.size() > 0) {
            //construct access control policy from grant infos
            BucketInfo bucket = bucketList.get(0);
            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if (objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canReadACP(userId)) {
                        ownerId = objectInfo.getOwnerId();
                        ArrayList<Grant> grants = new ArrayList<Grant>();
                        List<GrantInfo> grantInfos = objectInfo.getGrants();
                        for (GrantInfo grantInfo: grantInfos) {
                            String uId = grantInfo.getUserId();
                            UserInfo userInfo = new UserInfo(uId);
                            EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
                            List<UserInfo> grantUserInfos = db2.query(userInfo);
                            db2.commit();
                            if(grantUserInfos.size() > 0) {
                                objectInfo.readPermissions(grants);
                                addPermission(grants, grantUserInfos.get(0), grantInfo);
                            }
                        }
                        accessControlList.setGrants(grants);
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }

        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        UserInfo userInfo = new UserInfo(ownerId);
        EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
        List<UserInfo> ownerUserInfos = db2.query(userInfo);
        db2.commit();

        AccessControlPolicyType accessControlPolicy = new AccessControlPolicyType();
        if(ownerUserInfos.size() > 0) {
            UserInfo ownerUserInfo = ownerUserInfos.get(0);
            accessControlPolicy.setOwner(new CanonicalUserType(ownerUserInfo.getQueryId(), ownerUserInfo.getUserName()));
            accessControlPolicy.setAccessControlList(accessControlList);
            reply.setAccessControlPolicy(accessControlPolicy);
        }
        db.commit();
        return reply;
    }

    public SetBucketAccessControlPolicyResponseType SetBucketAccessControlPolicy(SetBucketAccessControlPolicyType request) throws EucalyptusCloudException
    {
        SetBucketAccessControlPolicyResponseType reply = (SetBucketAccessControlPolicyResponseType) request.getReply();
        String userId = request.getUserId();
        AccessControlPolicyType accessControlPolicy = request.getAccessControlPolicy();
        if(accessControlPolicy == null) {
            throw new AccessDeniedException(userId);
        }
        String bucketName = request.getBucket();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if (bucket.canWriteACP(userId)) {
                List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                AccessControlListType accessControlList = accessControlPolicy.getAccessControlList();
                bucket.resetGlobalGrants();
                bucket.addGrants(bucket.getOwnerId(), grantInfos, accessControlList);
                bucket.setGrants(grantInfos);
                reply.setCode("204");
                reply.setDescription("OK");
            } else {
                db.rollback();
                throw new AccessDeniedException(bucketName);
            }
        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public SetObjectAccessControlPolicyResponseType SetObjectAccessControlPolicy(SetObjectAccessControlPolicyType request) throws EucalyptusCloudException
    {
        SetObjectAccessControlPolicyResponseType reply = (SetObjectAccessControlPolicyResponseType) request.getReply();
        String userId = request.getUserId();
        AccessControlPolicyType accessControlPolicy = request.getAccessControlPolicy();
        String bucketName = request.getBucket();
        String objectKey = request.getKey();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            ObjectInfo foundObject = null;
            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if (objectInfo.canWriteACP(userId)) {
                        foundObject = objectInfo;
                        break;
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }

            if(foundObject != null) {
                List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                AccessControlListType accessControlList = accessControlPolicy.getAccessControlList();
                foundObject.resetGlobalGrants();
                foundObject.addGrants(foundObject.getOwnerId(), grantInfos, accessControlList);
                foundObject.setGrants(grantInfos);

                reply.setCode("204");
                reply.setDescription("OK");
            } else {
                db.rollback();
                throw new NoSuchEntityException(objectKey);
            }
        }   else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public GetObjectResponseType GetObject(GetObjectType request) throws EucalyptusCloudException {
        GetObjectResponseType reply = (GetObjectResponseType) request.getReply();
        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();
        Boolean deleteAfterGet = request.getDeleteAfterGet();
        if(deleteAfterGet == null)
            deleteAfterGet = false;

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId)) {
                        String objectName = objectInfo.getObjectName();
                        if(request.getGetMetaData()) {
                            ArrayList<MetaDataEntry> metaData = new ArrayList<MetaDataEntry>();
                            objectInfo.returnMetaData(metaData);
                            reply.setMetaData(metaData);
                            reply.setMetaData(metaData);
                        }
                        if(request.getGetData()) {
                            if(request.getInlineData()) {
                                try {
                                    byte[] bytes = new byte[WalrusQueryDispatcher.DATA_MESSAGE_SIZE];
                                    int bytesRead = 0;
                                    String base64Data = "";
                                    while((bytesRead = storageManager.readObject(bucketName, objectName, bytes, bytesRead)) > 0) {
                                        base64Data += new String(bytes, 0, bytesRead);
                                    }
                                    reply.setBase64Data(base64Data);
                                } catch (IOException ex) {
                                    db.rollback();
                                    //set error code
                                    return reply;
                                }
                            } else {
                                //support for large objects
                                String key = bucketName + "." + objectKey;
                                String randomKey = key + "." + Hashes.getRandom(10);
                                request.setRandomKey(randomKey);
                                LinkedBlockingQueue<WalrusDataMessage> getQueue = WalrusQueryDispatcher.getReadMessenger().getQueue(key, randomKey);

                                Reader reader = new Reader(bucketName, objectName, objectInfo.getSize(), getQueue, deleteAfterGet, null);
                                reader.start();
                            }
                        }
                        reply.setEtag(objectInfo.getEtag());
                        reply.setLastModified(DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
                        reply.setSize(objectInfo.getSize());
                        Status status = new Status();
                        status.setCode(200);
                        status.setDescription("OK");
                        reply.setStatus(status);
                        db.commit();
                        return reply;
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }
            db.rollback();
            throw new NoSuchEntityException(objectKey);
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
    }

    public GetObjectExtendedResponseType GetObjectExtended(GetObjectExtendedType request) throws EucalyptusCloudException {
        GetObjectExtendedResponseType reply = (GetObjectExtendedResponseType) request.getReply();
        Long byteRangeStart = request.getByteRangeStart();
        if(byteRangeStart == null) {
            byteRangeStart = 0L;
        }
        Long byteRangeEnd = request.getByteRangeEnd();
        if(byteRangeEnd == null) {
            byteRangeEnd = -1L;
        }
        Date ifModifiedSince = request.getIfModifiedSince();
        Date ifUnmodifiedSince = request.getIfUnmodifiedSince();
        String ifMatch = request.getIfMatch();
        String ifNoneMatch = request.getIfNoneMatch();
        boolean returnCompleteObjectOnFailure = request.getReturnCompleteObjectOnConditionFailure();

        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();
        Status status = new Status();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);


        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId)) {
                        String etag = objectInfo.getEtag();
                        String objectName = objectInfo.getObjectName();
                        if(ifMatch != null) {
                            if(!ifMatch.equals(etag) && !returnCompleteObjectOnFailure) {
                                db.rollback();
                                throw new PreconditionFailedException(etag);
                            }

                        }
                        if(ifNoneMatch != null) {
                            if(ifNoneMatch.equals(etag) && !returnCompleteObjectOnFailure) {
                                db.rollback();
                                throw new NotModifiedException(etag);
                            }
                        }
                        Date lastModified = objectInfo.getLastModified();
                        if(ifModifiedSince != null) {
                            if((ifModifiedSince.getTime() >= lastModified.getTime()) && !returnCompleteObjectOnFailure) {
                                db.rollback();
                                throw new NotModifiedException(lastModified.toString());
                            }
                        }
                        if(ifUnmodifiedSince != null) {
                            if((ifUnmodifiedSince.getTime() < lastModified.getTime()) && !returnCompleteObjectOnFailure) {
                                db.rollback();
                                throw new PreconditionFailedException(lastModified.toString());
                            }
                        }
                        if(request.getGetMetaData()) {
                            ArrayList<MetaDataEntry> metaData = new ArrayList<MetaDataEntry>();
                            objectInfo.returnMetaData(metaData);
                            reply.setMetaData(metaData);
                        }
                        if(request.getGetData()) {
                            String key = bucketName + "." + objectKey;
                            String randomKey = key + "." + Hashes.getRandom(10);
                            request.setRandomKey(randomKey);
                            LinkedBlockingQueue<WalrusDataMessage> getQueue = WalrusQueryDispatcher.getReadMessenger().getQueue(key, randomKey);

                            Reader reader = new Reader(bucketName, objectName, objectInfo.getSize(), getQueue, byteRangeStart, byteRangeEnd);
                            reader.start();
                        }
                        reply.setEtag(objectInfo.getEtag());
                        reply.setLastModified(DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
                        if(byteRangeEnd > -1) {
                            if(byteRangeEnd <= objectInfo.getSize() && ((byteRangeEnd - byteRangeStart) > 0))
                                reply.setSize(byteRangeEnd - byteRangeStart);
                            else
                                reply.setSize(0L);
                        } else {
                            reply.setSize(objectInfo.getSize());
                        }
                        status.setCode(200);
                        status.setDescription("OK");
                        reply.setStatus(status);
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }
        }
        db.commit();
        return reply;
    }

    public GetBucketLocationResponseType GetBucketLocation(GetBucketLocationType request) throws EucalyptusCloudException {
        GetBucketLocationResponseType reply = (GetBucketLocationResponseType) request.getReply();
        String bucketName = request.getBucket();
        String userId = request.getUserId();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if(bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);
            if(bucket.canRead(userId)) {
                String location = bucket.getLocation();
                if(location == null) {
                    location = "NotSupported";
                }
                reply.setLocationConstraint(location);
            } else {
                db.rollback();
                throw new AccessDeniedException(userId);
            }
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
        db.commit();
        return reply;
    }

    public CopyObjectResponseType CopyObject(CopyObjectType request) throws EucalyptusCloudException {
        CopyObjectResponseType reply = (CopyObjectResponseType) request.getReply();
        String userId = request.getUserId();
        String sourceBucket = request.getSourceBucket();
        String sourceKey = request.getSourceObject();
        String destinationBucket = request.getDestinationBucket();
        String destinationKey = request.getDestinationObject();
        String metadataDirective = request.getMetadataDirective();
        AccessControlListType accessControlList = request.getAccessControlList();

        String copyIfMatch = request.getCopySourceIfMatch();
        String copyIfNoneMatch = request.getCopySourceIfNoneMatch();
        Date copyIfUnmodifiedSince = request.getCopySourceIfUnmodifiedSince();
        Date copyIfModifiedSince = request.getCopySourceIfModifiedSince();

        if(metadataDirective == null)
            metadataDirective = "COPY";
        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(sourceBucket);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(sourceKey)) {
                    ObjectInfo sourceObjectInfo = objectInfo;
                    if(sourceObjectInfo.canRead(userId)) {
                        if(copyIfMatch != null) {
                            if(!copyIfMatch.equals(sourceObjectInfo.getEtag())) {
                                db.rollback();
                                throw new PreconditionFailedException("CopySourceIfMatch " + copyIfMatch);
                            }
                        }
                        if(copyIfNoneMatch != null) {
                            if(copyIfNoneMatch.equals(sourceObjectInfo.getEtag())) {
                                db.rollback();
                                throw new PreconditionFailedException("CopySourceIfNoneMatch " + copyIfNoneMatch);
                            }
                        }
                        if(copyIfUnmodifiedSince != null) {
                            long unmodifiedTime = copyIfUnmodifiedSince.getTime();
                            long objectTime = sourceObjectInfo.getLastModified().getTime();
                            if(unmodifiedTime < objectTime) {
                                db.rollback();
                                throw new PreconditionFailedException("CopySourceIfUnmodifiedSince " + copyIfUnmodifiedSince.toString());
                            }
                        }
                        if(copyIfModifiedSince != null) {
                            long modifiedTime = copyIfModifiedSince.getTime();
                            long objectTime = sourceObjectInfo.getLastModified().getTime();
                            if(modifiedTime > objectTime) {
                                db.rollback();
                                throw new PreconditionFailedException("CopySourceIfModifiedSince " + copyIfModifiedSince.toString());
                            }
                        }
                        BucketInfo destinationBucketInfo = new BucketInfo(destinationBucket);
                        List<BucketInfo> destinationBuckets = db.query(destinationBucketInfo);
                        if(destinationBuckets.size() > 0) {
                            BucketInfo foundDestinationBucketInfo = destinationBuckets.get(0);
                            if(foundDestinationBucketInfo.canWrite(userId)) {
                                //all ok
                                ObjectInfo destinationObjectInfo = null;
                                String destinationObjectName;
                                for(ObjectInfo objInfo: foundDestinationBucketInfo.getObjects()) {
                                    if(objInfo.getObjectKey().equals(destinationKey)) {
                                        destinationObjectInfo = objInfo;
                                        if(!destinationObjectInfo.canWrite(userId)) {
                                            db.rollback();
                                            throw new AccessDeniedException(destinationKey);
                                        }
                                        break;
                                    }
                                }
                                if(destinationObjectInfo == null) {
                                    //not found. create a new one
                                    destinationObjectInfo = new ObjectInfo();
                                    List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                                    destinationObjectInfo.setObjectKey(destinationKey);
                                    destinationObjectInfo.addGrants(userId, grantInfos, accessControlList);
                                    destinationObjectInfo.setGrants(grantInfos);
                                    destinationObjectInfo.setObjectName(destinationKey.replaceAll("/", "-") + Hashes.getRandom(4));
                                    foundDestinationBucketInfo.getObjects().add(destinationObjectInfo);
                                } else {
                                    if (destinationObjectInfo.canWriteACP(userId)) {
                                        List<GrantInfo> grantInfos = new ArrayList<GrantInfo>();
                                        destinationObjectInfo.addGrants(userId, grantInfos, accessControlList);
                                        destinationObjectInfo.setGrants(grantInfos);
                                    }
                                }

                                destinationObjectInfo.setSize(sourceObjectInfo.getSize());
                                destinationObjectInfo.setStorageClass(sourceObjectInfo.getStorageClass());
                                destinationObjectInfo.setOwnerId(sourceObjectInfo.getOwnerId());
                                String etag = sourceObjectInfo.getEtag();
                                Date lastModified = sourceObjectInfo.getLastModified();
                                destinationObjectInfo.setEtag(etag);
                                destinationObjectInfo.setLastModified(lastModified);
                                if(!metadataDirective.equals("REPLACE")) {
                                    destinationObjectInfo.setMetaData(sourceObjectInfo.cloneMetaData());
                                } else {
                                    List<MetaDataEntry> metaData = request.getMetaData();
                                    if(metaData != null)
                                        destinationObjectInfo.replaceMetaData(metaData);
                                }

                                String sourceObjectName = sourceObjectInfo.getObjectName();
                                destinationObjectName = destinationObjectInfo.getObjectName();

                                try {
                                    storageManager.copyObject(sourceBucket, sourceObjectName, destinationBucket, destinationObjectName);
                                } catch(Exception ex) {
                                    LOG.error(ex);
                                    db.rollback();
                                    throw new EucalyptusCloudException("Could not rename " + sourceObjectName + " to " + destinationObjectName);
                                }
                                reply.setEtag(etag);
                                reply.setLastModified(DateUtils.format(lastModified.getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");

                                db.commit();
                                return reply;
                            } else {
                                db.rollback();
                                throw new AccessDeniedException(destinationBucket);
                            }
                        } else {
                            db.rollback();
                            throw new NoSuchBucketException(destinationBucket);
                        }
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(sourceKey);
                    }
                }
            }
            db.rollback();
            throw new NoSuchEntityException(sourceKey);
        } else {
            db.rollback();
            throw new NoSuchBucketException(sourceBucket);
        }
    }

    public GetBucketLoggingStatusResponseType GetBucketLoggingStatus(GetBucketLoggingStatusType request) throws EucalyptusCloudException {
        GetBucketLoggingStatusResponseType reply = (GetBucketLoggingStatusResponseType) request.getReply();

        throw new NotImplementedException("GetBucketLoggingStatus");
    }

    public SetBucketLoggingStatusResponseType SetBucketLoggingStatus(SetBucketLoggingStatusType request) throws EucalyptusCloudException {
        SetBucketLoggingStatusResponseType reply = (SetBucketLoggingStatusResponseType) request.getReply();

        throw new NotImplementedException("SetBucketLoggingStatus");
    }

    private boolean canVerifySignature(Signature sigVerifier, X509Certificate cert, String signature, String verificationString) throws Exception {
        PublicKey publicKey = cert.getPublicKey();
        sigVerifier.initVerify(publicKey);
        sigVerifier.update((verificationString).getBytes());
        return sigVerifier.verify(hexToBytes(signature));
    }

    private String decryptImage(String bucketName, String objectKey, String userId, boolean isAdministrator) throws EucalyptusCloudException {
        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);


        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId)) {
                        String objectName = objectInfo.getObjectName();
                        File file = new File(storageManager.getObjectPath(bucketName, objectName));
                        XMLParser parser = new XMLParser(file);
//Read manifest
                        String imageKey = parser.getValue("//image/name");
                        String encryptedKey = parser.getValue("//ec2_encrypted_key");
                        String encryptedIV = parser.getValue("//ec2_encrypted_iv");
                        String signature = parser.getValue("//signature");

                        AbstractKeyStore userKeyStore = UserKeyStore.getInstance();

                        String image = parser.getXML("image");
                        String machineConfiguration = parser.getXML("machine_configuration");

                        String verificationString = machineConfiguration + image;

                        Signature sigVerifier;
                        try {
                            sigVerifier = Signature.getInstance("SHA1withRSA");
                        } catch (NoSuchAlgorithmException ex) {
                            LOG.error(ex, ex);
                            throw new DecryptionFailedException("SHA1withRSA not found");
                        }


                        if(isAdministrator) {
                            try {
                                boolean verified = false;
                                List<String> aliases = userKeyStore.getAliases();
                                for(String alias : aliases) {
                                    X509Certificate cert = userKeyStore.getCertificate(alias);
                                    verified = canVerifySignature(sigVerifier, cert, signature, verificationString);
                                    if(verified)
                                        break;
                                }
                                if(!verified) {
                                    throw new NotAuthorizedException("Invalid signature");
                                }
                            } catch (Exception ex) {
                                db.rollback();
                                LOG.error(ex, ex);
                                throw new DecryptionFailedException("signature verification");
                            }
                        } else {
                            EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
                            UserInfo userInfo = new UserInfo(userId);
                            List<UserInfo> foundUserInfos = db2.query(userInfo);
                            if(foundUserInfos.size() == 0) {
                                db2.rollback();
                                db.rollback();
                                throw new AccessDeniedException(userId);
                            }
                            List<CertificateInfo> certInfos = foundUserInfos.get(0).getCertificates();
                            boolean signatureVerified = false;
                            for(CertificateInfo certInfo: certInfos) {
                                String alias = certInfo.getCertAlias();
                                try {
                                    X509Certificate cert = userKeyStore.getCertificate(alias);
                                    signatureVerified = canVerifySignature(sigVerifier, cert, signature, verificationString);
                                    if (signatureVerified)
                                        break;
                                } catch(Exception ex) {
                                    db2.rollback();
                                    db.rollback();
                                    LOG.error(ex, ex);
                                    throw new DecryptionFailedException("signature verification");
                                }
                            }
                            if(!signatureVerified) {
                                throw new NotAuthorizedException("Invalid signature");
                            }
                            db2.commit();
                        }
                        List<String> parts = parser.getValues("//image/parts/part/filename");
                        ArrayList<String> qualifiedPaths = new ArrayList<String>();

                        for (String part: parts) {
                            for(ObjectInfo object : bucket.getObjects()) {
                                if(part.equals(object.getObjectKey())) {
                                    qualifiedPaths.add(storageManager.getObjectPath(bucketName, object.getObjectName()));
                                }
                            }
                        }
                        //Assemble parts
                        String encryptedImageKey = imageKey + "-" + Hashes.getRandom(5) + ".crypt.gz";
                        String encryptedImageName = storageManager.getObjectPath(bucketName, encryptedImageKey);
                        String decryptedImageKey = encryptedImageKey.replaceAll("crypt.gz", "tgz");
                        String decryptedImageName = storageManager.getObjectPath(bucketName, decryptedImageKey);
                        assembleParts(encryptedImageName, qualifiedPaths);
//Decrypt key and IV

                        byte[] key;
                        byte[] iv;
                        try {
                            PrivateKey pk = (PrivateKey) userKeyStore.getKey(EucalyptusProperties.NAME, EucalyptusProperties.NAME);
                            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                            cipher.init(Cipher.DECRYPT_MODE, pk);
                            String keyString = new String(cipher.doFinal(hexToBytes(encryptedKey)));
                            key = hexToBytes(keyString);
                            String ivString = new String(cipher.doFinal(hexToBytes(encryptedIV)));
                            iv = hexToBytes(ivString);
                        } catch(Exception ex) {
                            db.rollback();
                            LOG.error(ex, ex);
                            throw new DecryptionFailedException("AES params");
                        }

                        //Unencrypt image
                        try {
                            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "BC");
                            IvParameterSpec salt = new IvParameterSpec(iv);
                            SecretKey keySpec = new SecretKeySpec(key, "AES");
                            cipher.init(Cipher.DECRYPT_MODE, keySpec, salt);
                            decryptImage(encryptedImageName, decryptedImageName, cipher);
                        } catch (Exception ex) {
                            db.rollback();
                            LOG.error(ex, ex);
                            throw new DecryptionFailedException("decryption failed");
                        }
                        try {
                            storageManager.deleteAbsoluteObject(encryptedImageName);
                        } catch (Exception ex) {
                            LOG.error(ex);
                            throw new EucalyptusCloudException();
                        }
                        db.commit();
                        return decryptedImageKey;
                    }
                }
            }
        }
        return null;
    }

    private void checkManifest(String bucketName, String objectKey, String userId) throws EucalyptusCloudException {
        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);


        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId)) {
                        String objectName = objectInfo.getObjectName();
                        File file = new File(storageManager.getObjectPath(bucketName, objectName));
                        XMLParser parser = new XMLParser(file);
//Read manifest
                        String encryptedKey = parser.getValue("//ec2_encrypted_key");
                        String encryptedIV = parser.getValue("//ec2_encrypted_iv");
                        String signature = parser.getValue("//signature");

                        AbstractKeyStore userKeyStore = UserKeyStore.getInstance();

                        String image = parser.getXML("image");
                        String machineConfiguration = parser.getXML("machine_configuration");

                        EntityWrapper<UserInfo> db2 = new EntityWrapper<UserInfo>();
                        UserInfo userInfo = new UserInfo(userId);
                        List<UserInfo> foundUserInfos = db2.query(userInfo);
                        if(foundUserInfos.size() == 0) {
                            db2.rollback();
                            db.rollback();
                            throw new AccessDeniedException(userId);
                        }

                        List<CertificateInfo> certInfos = foundUserInfos.get(0).getCertificates();
                        boolean signatureVerified = false;

                        Signature sigVerifier;
                        try {
                            sigVerifier = Signature.getInstance("SHA1withRSA");
                        } catch (NoSuchAlgorithmException ex) {
                            LOG.error(ex, ex);
                            throw new DecryptionFailedException("SHA1withRSA not found");
                        }

                        for(CertificateInfo certInfo: certInfos) {
                            String alias = certInfo.getCertAlias();
                            try {
                                X509Certificate cert = userKeyStore.getCertificate(alias);
                                PublicKey publicKey = cert.getPublicKey();
                                sigVerifier.initVerify(publicKey);
                                sigVerifier.update((machineConfiguration + image).getBytes());
                                signatureVerified = sigVerifier.verify(hexToBytes(signature));
                                if (signatureVerified) {
                                    break;
                                }
                            } catch(Exception ex) {
                                db2.rollback();
                                db.rollback();
                                LOG.error(ex, ex);
                                throw new DecryptionFailedException("signature verification");
                            }
                        }

                        if(!signatureVerified) {
                            throw new NotAuthorizedException("Invalid signature");
                        }
                        //Decrypt key and IV

                        byte[] key;
                        byte[] iv;
                        try {
                            PrivateKey pk = (PrivateKey) userKeyStore.getKey(EucalyptusProperties.NAME, EucalyptusProperties.NAME);
                            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                            cipher.init(Cipher.DECRYPT_MODE, pk);
                            key = hexToBytes(new String(cipher.doFinal(hexToBytes(encryptedKey))));
                            iv = hexToBytes(new String(cipher.doFinal(hexToBytes(encryptedIV))));
                        } catch(Exception ex) {
                            db2.rollback();
                            db.rollback();
                            LOG.error(ex, ex);
                            throw new DecryptionFailedException("AES params");
                        }
                        db2.commit();
                        db.commit();
                    }
                }
            }
        }
    }

    private boolean isCached(String bucketName, String manifestKey) {
        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
        try {
            ImageCacheInfo foundImageCacheInfo = db.getUnique(searchImageCacheInfo);
            if(foundImageCacheInfo.getInCache())
                return true;
            else
                return false;
        } catch(Exception ex) {
            return false;
        } finally {
            db.commit();
        }
    }

    private long checkCachingProgress(String bucketName, String manifestKey, long oldBytesRead) {
        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
        try {
            ImageCacheInfo foundImageCacheInfo = db.getUnique(searchImageCacheInfo);
            String cacheImageKey = foundImageCacheInfo.getImageName().replaceAll(".tgz", "");
            long objectSize = storageManager.getObjectSize(bucketName, cacheImageKey);
            if(objectSize > 0) {
                return objectSize - oldBytesRead;
            }
            return oldBytesRead;
        } catch (Exception ex) {
            return oldBytesRead;
        } finally {
            db.commit();
        }
    }

    public GetDecryptedImageResponseType GetDecryptedImage(GetDecryptedImageType request) throws EucalyptusCloudException {
        GetDecryptedImageResponseType reply = (GetDecryptedImageResponseType) request.getReply();
        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);

        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId) || request.isAdministrator() ) {
                        WalrusSemaphore semaphore = imageMessenger.getSemaphore(bucketName + "/" + objectKey);
                        try {
                            semaphore.acquire();
                        } catch(InterruptedException ex) {
                            throw new EucalyptusCloudException("semaphore could not be acquired");
                        }
                        EntityWrapper<ImageCacheInfo> db2 = new EntityWrapper<ImageCacheInfo>();
                        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, objectKey);
                        List<ImageCacheInfo> foundImageCacheInfos = db2.query(searchImageCacheInfo);

                        if((foundImageCacheInfos.size() == 0) || (!imageCachers.containsKey(bucketName + objectKey))) {
                            db2.commit();
//issue a cache request
                            cacheImage(bucketName, objectKey, userId, request.isAdministrator());
//query db again
                            db2 = new EntityWrapper<ImageCacheInfo>();
                            foundImageCacheInfos = db2.query(searchImageCacheInfo);
                        }
                        ImageCacheInfo foundImageCacheInfo = null;
                        if(foundImageCacheInfos.size() > 0)
                            foundImageCacheInfo = foundImageCacheInfos.get(0);
                        if((foundImageCacheInfo == null) || (!foundImageCacheInfo.getInCache())) {
                            boolean cached = false;
                            WalrusMonitor monitor = imageMessenger.getMonitor(bucketName + "/" + objectKey);
                            synchronized (monitor) {
                                try {
                                    long bytesCached = 0;
                                    int number_of_tries = 0;
                                    do {
                                        monitor.wait(CACHE_PROGRESS_TIMEOUT);
                                        if(isCached(bucketName, objectKey)) {
                                            cached = true;
                                            break;
                                        }
                                        long newBytesCached = checkCachingProgress(bucketName, objectKey, bytesCached);
                                        boolean is_caching = (newBytesCached - bytesCached) > 0 ? true : false;
                                        if (!is_caching && (number_of_tries++ >= CACHE_RETRY_LIMIT))
                                            break;

                                        bytesCached = newBytesCached;
                                        if(is_caching) {
                                            LOG.info("Bytes cached so far for image " + bucketName + "/" + objectKey + " :" +  String.valueOf(bytesCached));
                                        }
                                    } while(true);
                                } catch(Exception ex) {
                                    LOG.error(ex);
                                    db2.rollback();
                                    db.rollback();
                                    throw new EucalyptusCloudException("monitor failure");
                                }
                            }
                            if(!cached) {
                                LOG.error("Tired of waiting to cache image: " + bucketName + "/" + objectKey + " giving up");
                                db2.rollback();
                                db.rollback();
                                throw new EucalyptusCloudException("caching failure");
                            }
                            //caching may have modified the db. repeat the query
                            db2.commit();
                            db2 = new EntityWrapper<ImageCacheInfo>();
                            foundImageCacheInfos = db2.query(searchImageCacheInfo);
                            if(foundImageCacheInfos.size() > 0) {
                                foundImageCacheInfo = foundImageCacheInfos.get(0);
                                foundImageCacheInfo.setUseCount(foundImageCacheInfo.getUseCount() + 1);
                                assert(foundImageCacheInfo.getInCache());
                            } else {
                                db.rollback();
                                db2.rollback();
                                throw new NoSuchEntityException(objectKey);
                            }
                        }

                        Long unencryptedSize = foundImageCacheInfo.getSize();

                        String imageKey = foundImageCacheInfo.getImageName();
                        String queueKey = bucketName + "." + objectKey;
                        String randomKey = queueKey + "." + Hashes.getRandom(10);
                        request.setRandomKey(randomKey);

                        LinkedBlockingQueue<WalrusDataMessage> getQueue = WalrusQueryDispatcher.getReadMessenger().getQueue(queueKey, randomKey);
                        reply.setSize(unencryptedSize);
                        reply.setLastModified(DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
                        reply.setEtag("");
                        Reader reader = new Reader(bucketName, imageKey, unencryptedSize, getQueue, false, semaphore);
                        reader.start();
                        db.commit();
                        db2.commit();
                        return reply;
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }
            db.rollback();
            throw new NoSuchEntityException(objectKey);
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
    }

    public CheckImageResponseType CheckImage(CheckImageType request) throws EucalyptusCloudException {
        CheckImageResponseType reply = (CheckImageResponseType) request.getReply();
        reply.setSuccess(false);
        String bucketName = request.getBucket();
        String objectKey = request.getKey();
        String userId = request.getUserId();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);


        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(objectKey)) {
                    if(objectInfo.canRead(userId)) {
                        checkManifest(bucketName, objectKey, userId);
                        reply.setSuccess(true);
                        db.commit();
                        return reply;
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(objectKey);
                    }
                }
            }
            db.rollback();
            throw new NoSuchEntityException(objectKey);
        } else {
            db.rollback();
            throw new NoSuchBucketException(bucketName);
        }
    }

    private synchronized void cacheImage(String bucketName, String manifestKey, String userId, boolean isAdministrator) throws EucalyptusCloudException {

        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
        List<ImageCacheInfo> imageCacheInfos = db.query(searchImageCacheInfo);
        String decryptedImageKey = null;
        if(imageCacheInfos.size() != 0) {
            ImageCacheInfo icInfo = imageCacheInfos.get(0);
            if(!icInfo.getInCache()) {
                decryptedImageKey = icInfo.getImageName();
            } else {
                db.commit();
                return;
            }
        }
        //unzip, untar image in the background
        ImageCacher imageCacher = imageCachers.putIfAbsent(bucketName + manifestKey, new ImageCacher(bucketName, manifestKey, decryptedImageKey));
        if(imageCacher == null) {
            if(decryptedImageKey == null) {
                decryptedImageKey = decryptImage(bucketName, manifestKey, userId, isAdministrator);
                //decryption worked. Add it.
                ImageCacheInfo foundImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
                foundImageCacheInfo.setImageName(decryptedImageKey);
                foundImageCacheInfo.setInCache(false);
                foundImageCacheInfo.setUseCount(0);
                foundImageCacheInfo.setSize(0L);
                db.add(foundImageCacheInfo);
            }
            db.commit();
            imageCacher = imageCachers.get(bucketName + manifestKey);
            imageCacher.setDecryptedImageKey(decryptedImageKey);
            imageCacher.start();
        } else {
            db.commit();
        }
    }

    public CacheImageResponseType CacheImage(CacheImageType request) throws EucalyptusCloudException {
        CacheImageResponseType reply = (CacheImageResponseType) request.getReply();
        reply.setSuccess(false);
        String bucketName = request.getBucket();
        String manifestKey = request.getKey();
        String userId = request.getUserId();

        EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
        BucketInfo bucketInfo = new BucketInfo(bucketName);
        List<BucketInfo> bucketList = db.query(bucketInfo);


        if (bucketList.size() > 0) {
            BucketInfo bucket = bucketList.get(0);

            for(ObjectInfo objectInfo: bucket.getObjects()) {
                if(objectInfo.getObjectKey().equals(manifestKey)) {
                    if(objectInfo.canRead(userId)) {
                        EntityWrapper<ImageCacheInfo> db2 = new EntityWrapper<ImageCacheInfo>();
                        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
                        List<ImageCacheInfo> foundImageCacheInfos = db2.query(searchImageCacheInfo);
                        db2.commit();
                        if((foundImageCacheInfos.size() == 0) || (!imageCachers.containsKey(bucketName + manifestKey))) {
                            cacheImage(bucketName, manifestKey, userId, request.isAdministrator());
                            reply.setSuccess(true);
                        }
                        return reply;
                    } else {
                        db.rollback();
                        throw new AccessDeniedException(manifestKey);
                    }

                }
            }
        }
        throw new NoSuchEntityException(manifestKey);
    }

    public FlushCachedImageResponseType FlushCachedImage(FlushCachedImageType request) throws EucalyptusCloudException {
        FlushCachedImageResponseType reply = (FlushCachedImageResponseType) request.getReply();

        String bucketName = request.getBucket();
        String manifestKey = request.getKey();

        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
        List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);

        if(foundImageCacheInfos.size() > 0) {
            ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
            if(foundImageCacheInfo.getInCache() && (imageCachers.get(bucketName + manifestKey) == null)) {
                //check that there are no operations in progress and then flush cache and delete image file
                db.commit();
                ImageCacheFlusher imageCacheFlusher = new ImageCacheFlusher(bucketName, manifestKey);
                imageCacheFlusher.start();
            } else {
                db.rollback();
                throw new EucalyptusCloudException("not in cache");
            }
        } else {
            db.rollback();
            throw new NoSuchEntityException(bucketName + manifestKey);
        }
        return reply;
    }

    private void flushCachedImage (String bucketName, String objectKey) throws Exception {
        WalrusSemaphore semaphore = imageMessenger.getSemaphore(bucketName + "/" + objectKey);
        while(semaphore.inUse()) {
            try {
                synchronized (semaphore) {
                    semaphore.wait();
                }
            } catch(InterruptedException ex) {
                LOG.error(ex);
            }
        }
        imageMessenger.removeSemaphore(bucketName + "/" + objectKey);
        EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
        ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, objectKey);
        List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);

        if(foundImageCacheInfos.size() > 0) {
            ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
            LOG.info("Attempting to flush cached image: " + bucketName + "/" + objectKey);
            if(foundImageCacheInfo.getInCache() && (imageCachers.get(bucketName + objectKey) == null)) {
                db.delete(foundImageCacheInfo);
                storageManager.deleteObject(bucketName, foundImageCacheInfo.getImageName());
            }
            db.commit();
        } else {
            db.rollback();
            LOG.warn("Cannot find image in cache" + bucketName + "/" + objectKey);
        }
    }

    private class ImageCacheFlusher extends Thread {
        private String bucketName;
        private String objectKey;

        public ImageCacheFlusher(String bucketName, String objectKey) {
            this.bucketName = bucketName;
            this.objectKey = objectKey;
        }

        public void run() {
            try {
                flushCachedImage(bucketName, objectKey);
            } catch(Exception ex) {
                LOG.error(ex);
            }
        }
    }


    private class ImageCacher extends Thread {

        private String bucketName;
        private String manifestKey;
        private String decryptedImageKey;
        private boolean imageSizeExceeded;
        private long spaceNeeded;

        public ImageCacher(String bucketName, String manifestKey, String decryptedImageKey) {
            this.bucketName = bucketName;
            this.manifestKey = manifestKey;
            this.decryptedImageKey = decryptedImageKey;
            this.imageSizeExceeded = false;

        }

        public void setDecryptedImageKey(String key) {
            this.decryptedImageKey = key;
        }

        private long tryToCache(String decryptedImageName, String tarredImageName, String imageName) {
            Long unencryptedSize = 0L;
            boolean failed = false;
            try {
                if(!imageSizeExceeded) {
                    LOG.info("Unzipping image: " + bucketName + "/" + manifestKey);
                    unzipImage(decryptedImageName, tarredImageName);
                    LOG.info("Untarring image: " + bucketName + "/" + manifestKey);
                    unencryptedSize = untarImage(tarredImageName, imageName);
                } else {
                    File imageFile = new File(imageName);
                    if(imageFile.exists()) {
                        unencryptedSize = imageFile.length();
                    } else {
                        LOG.error("Could not find image: " + imageName);
                        imageSizeExceeded = false;
                        return -1L;
                    }

                }
                Long oldCacheSize = 0L;
                EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
                List<ImageCacheInfo> imageCacheInfos = db.query(new ImageCacheInfo());
                for(ImageCacheInfo imageCacheInfo: imageCacheInfos) {
                    if(imageCacheInfo.getInCache()) {
                        oldCacheSize += imageCacheInfo.getSize();
                    }
                }
                db.commit();
                if((oldCacheSize + unencryptedSize) > WalrusProperties.IMAGE_CACHE_SIZE) {
                    LOG.error("Maximum image cache size exceeded when decrypting " + bucketName + "/" + manifestKey);
                    failed = true;
                    imageSizeExceeded = true;
                    spaceNeeded = unencryptedSize;
                }
            } catch(Exception ex) {
                LOG.warn(ex);
                //try to evict an entry and try again
                failed = true;
            }
            if(failed) {
                if(!imageSizeExceeded) {
                    try {
                        storageManager.deleteAbsoluteObject(tarredImageName);
                        storageManager.deleteAbsoluteObject(imageName);
                    } catch (Exception exception) {
                        LOG.error(exception);
                    }
                }
                return -1L;
            }
            LOG.info("Cached image: " + bucketName + "/" + manifestKey + " size: " + String.valueOf(unencryptedSize));
            return unencryptedSize;
        }

        private void notifyWaiters() {
            WalrusMonitor monitor = imageMessenger.getMonitor(bucketName + "/" + manifestKey);
            synchronized (monitor) {
                monitor.notifyAll();
            }
            imageMessenger.removeMonitor(bucketName + "/" + manifestKey);
            imageCachers.remove(bucketName + manifestKey);
        }

        public void run() {
            //update status
            //wake up any waiting consumers
            String decryptedImageName = storageManager.getObjectPath(bucketName, decryptedImageKey);
            String tarredImageName = decryptedImageName.replaceAll("tgz", "tar");
            String imageName = tarredImageName.replaceAll(".tar", "");
            String imageKey = decryptedImageKey.replaceAll(".tgz", "");
            Long unencryptedSize;
            int numberOfRetries = 0;
            while((unencryptedSize = tryToCache(decryptedImageName, tarredImageName, imageName)) < 0) {
                try {
                    Thread.sleep(CACHE_RETRY_TIMEOUT);
                } catch(InterruptedException ex) {
                    notifyWaiters();
                    return;
                }
                CACHE_RETRY_TIMEOUT = 2*CACHE_RETRY_TIMEOUT;
                if(numberOfRetries++ >= CACHE_RETRY_LIMIT) {
                    notifyWaiters();
                    return;
                }
                EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
                ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo();
                searchImageCacheInfo.setInCache(true);
                List<ImageCacheInfo> imageCacheInfos = db.query(searchImageCacheInfo);
                if(imageCacheInfos.size() == 0) {
                    LOG.error("No cached images found to flush. Unable to cache image. Please check the error log and the image cache size.");
                    db.rollback();
                    notifyWaiters();
                    return;
                }
                Collections.sort(imageCacheInfos);
                db.commit();
                try {
                    if(spaceNeeded > 0) {
                        ArrayList<ImageCacheInfo> imagesToFlush = new ArrayList<ImageCacheInfo>();
                        long tryToFree = spaceNeeded;
                        for(ImageCacheInfo imageCacheInfo : imageCacheInfos) {
                            if(tryToFree <= 0)
                                break;
                            long imageSize = imageCacheInfo.getSize();
                            tryToFree -= imageSize;
                            imagesToFlush.add(imageCacheInfo);
                        }
                        if(imagesToFlush.size() == 0) {
                            LOG.error("Unable to flush existing images. Sorry.");
                            notifyWaiters();
                            return;
                        }
                        for(ImageCacheInfo imageCacheInfo : imagesToFlush) {
                            flushCachedImage(imageCacheInfo.getBucketName(), imageCacheInfo.getManifestName());
                        }
                    } else {
                        LOG.error("Unable to cache image. Unable to flush existing images.");
                        notifyWaiters();
                        return;
                    }
                } catch(Exception ex) {
                    LOG.error(ex);
                    LOG.error("Unable to flush previously cached image. Please increase your image cache size");
                    notifyWaiters();
                }
            }
            try {
                storageManager.deleteAbsoluteObject(decryptedImageName);
                storageManager.deleteAbsoluteObject(tarredImageName);

                EntityWrapper<ImageCacheInfo>db = new EntityWrapper<ImageCacheInfo>();
                ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
                List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);
                if(foundImageCacheInfos.size() > 0) {
                    ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
                    foundImageCacheInfo.setImageName(imageKey);
                    foundImageCacheInfo.setInCache(true);
                    foundImageCacheInfo.setSize(unencryptedSize);
                    db.commit();
                    //wake up waiters
                    notifyWaiters();
                } else {
                    db.rollback();
                    LOG.error("Could not expand image" + decryptedImageName);
                }
            } catch (Exception ex) {
                LOG.error(ex);
            }
        }
    }

    private void unzipImage(String decryptedImageName, String tarredImageName) throws Exception {
        GZIPInputStream in = new GZIPInputStream(new FileInputStream(new File(decryptedImageName)));
        File outFile = new File(tarredImageName);
        ReadableByteChannel inChannel = Channels.newChannel(in);
        WritableByteChannel outChannel = new FileOutputStream(outFile).getChannel();

        ByteBuffer buffer = ByteBuffer.allocate(WalrusQueryDispatcher.DATA_MESSAGE_SIZE);
        while (inChannel.read(buffer) != -1) {
            buffer.flip();
            outChannel.write(buffer);
            buffer.clear();
        }
        outChannel.close();
        inChannel.close();
    }

    private long untarImage(String tarredImageName, String imageName) throws Exception {
        /*TarInputStream in = new TarInputStream(new FileInputStream(new File(tarredImageName)));
        File outFile = new File(imageName);
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile));

        TarEntry tEntry = in.getNextEntry();
        assert(!tEntry.isDirectory());

        in.copyEntryContents(out);
        out.close();
        in.close();
        return outFile.length();*/

        //Workaround because TarInputStream is broken
        Tar tarrer = new Tar();
        tarrer.untar(tarredImageName, imageName);
        File outFile = new File(imageName);
        if(outFile.exists())
            return outFile.length();
        else
            throw new EucalyptusCloudException("Could not untar image " + imageName);
    }

    private class StreamConsumer extends Thread
    {
        private InputStream is;
        private File file;

        public StreamConsumer(InputStream is) {
            this.is = is;
        }

        public StreamConsumer(InputStream is, File file) {
            this(is);
            this.file = file;
        }

        public void run()
        {
            try
            {
                BufferedInputStream inStream = new BufferedInputStream(is);
                BufferedOutputStream outStream = null;
                if(file != null) {
                    outStream = new BufferedOutputStream(new FileOutputStream(file));
                }
                byte[] bytes = new byte[WalrusProperties.IO_CHUNK_SIZE];
                int bytesRead;
                while((bytesRead = inStream.read(bytes)) > 0) {
                    if(outStream != null) {
                        outStream.write(bytes, 0, bytesRead);
                    }
                }
                if(outStream != null)
                    outStream.close();
            } catch (IOException ex)
            {
                LOG.error(ex);
            }
        }
    }

    private class Tar {
        public void untar(String tarFile, String outFile) {
            try
            {
                Runtime rt = Runtime.getRuntime();
                Process proc = rt.exec(new String[]{ "/bin/tar", "xfO", tarFile});
                StreamConsumer error = new StreamConsumer(proc.getErrorStream());
                StreamConsumer output = new StreamConsumer(proc.getInputStream(), new File(outFile));
                error.start();
                output.start();
                int exitVal = proc.waitFor();
                output.join();
            } catch (Throwable t) {
                LOG.error(t);
            }
        }
    }

    private void decryptImage(final String encryptedImageName, final String decryptedImageName, final Cipher cipher) throws Exception {
        LOG.info("Decrypting image: " + decryptedImageName);
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(decryptedImageName)));
        File inFile = new File(encryptedImageName);
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(inFile));

        int bytesRead = 0;
        byte[] bytes = new byte[8192];

        while((bytesRead = in.read(bytes)) > 0) {
            byte[] outBytes = cipher.update(bytes, 0, bytesRead);
            out.write(outBytes);
        }
        byte[] outBytes = cipher.doFinal();
        out.write(outBytes);
        in.close();
        out.close();
        LOG.info("Done decrypting: " + decryptedImageName);
    }

    private void assembleParts(final String name, List<String> parts) {
        try {
            FileChannel out = new FileOutputStream(new File(name)).getChannel();
            for (String partName: parts) {
                FileChannel in = new FileInputStream(new File(partName)).getChannel();
                in.transferTo(0, in.size(), out);
                in.close();
            }
            out.close();
        } catch (Exception ex) {
            LOG.error(ex);
        }
    }

    private byte[] hexToBytes(String data) {
        int k = 0;
        byte[] results = new byte[data.length() / 2];
        for (int i = 0; i < data.length();) {
            results[k] = (byte) (Character.digit(data.charAt(i++), 16) << 4);
            results[k] += (byte) (Character.digit(data.charAt(i++), 16));
            k++;
        }

        return results;
    }


    private String bytesToHex(byte[] data) {
        StringBuffer buffer = new StringBuffer();
        for ( int i = 0; i < data.length; i++ ) {
            buffer.append( byteToHex(data[i]) );
        }
        return(buffer.toString());
    }

    private String byteToHex(byte data) {
        StringBuffer hexString =  new StringBuffer();
        hexString.append(toHex((data>>>4)&0x0F));
        hexString.append(toHex(data&0x0F));
        return hexString.toString();
    }

    private char toHex(int value) {
        if ((0 <= value) && (value <= 9 ))
            return (char)('0' + value);
        else
            return (char)('a' + (value-10));
    }

    class Reader extends Thread {

        private String bucketName;
        private String objectName;
        private long objectSize;
        private LinkedBlockingQueue<WalrusDataMessage> getQueue;
        private long byteRangeStart;
        private long byteRangeEnd;
        private boolean compressed;
        private boolean deleteAfterXfer;
        private WalrusSemaphore semaphore;

        public Reader(String bucketName, String objectName, long objectSize, LinkedBlockingQueue<WalrusDataMessage> getQueue) {
            this.bucketName = bucketName;
            this.objectName = objectName;
            this.objectSize = objectSize;
            this.getQueue = getQueue;
        }


        public Reader(String bucketName, String objectName, long objectSize, LinkedBlockingQueue<WalrusDataMessage> getQueue, long byteRangeStart, long byteRangeEnd) {
            this.bucketName = bucketName;
            this.objectName = objectName;
            this.objectSize = objectSize;
            this.getQueue = getQueue;
            this.byteRangeStart = byteRangeStart;
            this.byteRangeEnd = byteRangeEnd;
        }

        public Reader(String bucketName, String objectName, long objectSize, LinkedBlockingQueue<WalrusDataMessage> getQueue, boolean deleteAfterXfer, WalrusSemaphore semaphore) {
            this(bucketName, objectName, objectSize, getQueue);
            this.deleteAfterXfer = deleteAfterXfer;
            this.semaphore = semaphore;
        }

        public void run() {
            byte[] bytes = new byte[WalrusQueryDispatcher.DATA_MESSAGE_SIZE];

            long bytesRemaining = objectSize;
            long offset = byteRangeStart;

            if(byteRangeEnd > 0) {
                assert(byteRangeEnd <= objectSize);
                assert(byteRangeEnd >= byteRangeStart);
                bytesRemaining = byteRangeEnd - byteRangeStart;
                if(byteRangeEnd > objectSize || (byteRangeStart < 0))
                    bytesRemaining = 0;
            }

            try {
                getQueue.put(WalrusDataMessage.StartOfData(bytesRemaining));

                while (bytesRemaining > 0) {
                    int bytesRead = storageManager.readObject(bucketName, objectName, bytes, offset);
                    if(bytesRead < 0) {
                        LOG.error("Unable to read object: " + bucketName + "/" + objectName);
                        break;
                    }
                    if((bytesRemaining - bytesRead) > 0)
                        getQueue.put(WalrusDataMessage.DataMessage(bytes, bytesRead));
                    else
                        getQueue.put(WalrusDataMessage.DataMessage(bytes, (int)bytesRemaining));

                    bytesRemaining -= bytesRead;
                    offset += bytesRead;
                }
                getQueue.put(WalrusDataMessage.EOF());
            } catch (Exception ex) {
                LOG.error( ex,ex );
            }
            if(semaphore != null) {
                semaphore.release();
                synchronized (semaphore) {
                    semaphore.notifyAll();
                }
            }
            if(deleteAfterXfer) {
                try {
                    storageManager.deleteObject(bucketName, objectName);
                } catch(Exception ex) {
                    LOG.error( ex,ex );
                }
            }
        }
    }

    public StoreSnapshotResponseType StoreSnapshot(StoreSnapshotType request) throws EucalyptusCloudException {
        StoreSnapshotResponseType reply = (StoreSnapshotResponseType) request.getReply();

        if(!enableSnapshots) {
            LOG.warn("Snapshots not enabled. Please check pre-conditions and restart Walrus.");
            return reply;
        }
        String snapshotId = request.getKey();
        String bucketName = request.getBucket();
        String snapshotVgName = request.getSnapshotvgname();
        String snapshotLvName = request.getSnapshotlvname();
        boolean createBucket = true;

        EntityWrapper<WalrusSnapshotSet> db = new EntityWrapper<WalrusSnapshotSet>();
        WalrusSnapshotSet snapshotSet = new WalrusSnapshotSet(bucketName);
        List<WalrusSnapshotSet> snapshotSets = db.query(snapshotSet);

        WalrusSnapshotSet foundSnapshotSet;
        if(snapshotSets.size() == 0) {
            foundSnapshotSet = snapshotSet;
            createBucket = true;
            db.add(foundSnapshotSet);
        } else {
            foundSnapshotSet = snapshotSets.get(0);
        }

        List<WalrusSnapshotInfo> snapshotInfos = foundSnapshotSet.getSnapshotSet();
        EntityWrapper<WalrusSnapshotInfo> dbSnap = db.recast(WalrusSnapshotInfo.class);
        WalrusSnapshotInfo snapshotInfo = new WalrusSnapshotInfo(snapshotId);
        List<WalrusSnapshotInfo> snapInfos = dbSnap.query(snapshotInfo);
        if(snapInfos.size() > 0) {
            snapshotInfo = snapInfos.get(0);
            if(snapshotInfo.getTransferred()) {
                db.rollback();
                throw new EntityAlreadyExistsException(snapshotId);
            }
        } else {
            snapshotInfos.add(snapshotInfo);
            dbSnap.add(snapshotInfo);
        }

        //set snapshot props
        snapshotInfo.setSnapshotSetId(bucketName);
        snapshotInfo.setVgName(snapshotVgName);
        snapshotInfo.setLvName(snapshotLvName);
        snapshotInfo.setTransferred(false);
        //read and store it
        //convert to a PutObject request

        String userId = request.getUserId();
        if(createBucket) {
            CreateBucketType createBucketRequest = new CreateBucketType();
            createBucketRequest.setUserId(userId);
            createBucketRequest.setBucket(bucketName);
            try {
                CreateBucket(createBucketRequest);
            } catch(EucalyptusCloudException ex) {
                if(!(ex instanceof BucketAlreadyExistsException || ex instanceof BucketAlreadyOwnedByYouException)) {
                    db.rollback();
                    throw ex;
                }
            }
        }

        //put happens synchronously
        PutObjectType putObjectRequest = new PutObjectType();
        putObjectRequest.setUserId(userId);
        putObjectRequest.setBucket(bucketName);
        putObjectRequest.setKey(snapshotId);
        putObjectRequest.setRandomKey(request.getRandomKey());
        try {
            PutObjectResponseType putObjectResponseType = PutObject(putObjectRequest);
            reply.setEtag(putObjectResponseType.getEtag());
            reply.setLastModified(putObjectResponseType.getLastModified());
            reply.setStatusMessage(putObjectResponseType.getStatusMessage());
            int snapshotSize = (int)(putObjectResponseType.getSize() / WalrusProperties.G);
            if(shouldEnforceUsageLimits) {
                int totalSnapshotSize = 0;
                WalrusSnapshotInfo snapInfo = new WalrusSnapshotInfo();

                List<WalrusSnapshotInfo> sInfos = dbSnap.query(snapInfo);
                for (WalrusSnapshotInfo sInfo : sInfos) {
                    totalSnapshotSize += sInfo.getSize();
                }
                if((totalSnapshotSize + snapshotSize) > WalrusProperties.MAX_TOTAL_SNAPSHOT_SIZE) {
                    db.rollback();
                    throw new EntityTooLargeException(snapshotId);
                }
            }

            //change state
            db.commit();

            snapshotInfo = new WalrusSnapshotInfo(snapshotId);
            dbSnap = new EntityWrapper<WalrusSnapshotInfo>();
            WalrusSnapshotInfo foundSnapshotInfo = dbSnap.getUnique(snapshotInfo);
            foundSnapshotInfo.setSize(snapshotSize);
            foundSnapshotInfo.setTransferred(true);
            dbSnap.commit();
        } catch (EucalyptusCloudException ex) {
            db.rollback();
            throw ex;
        }
        return reply;
    }

    public GetSnapshotInfoResponseType GetSnapshotInfo(GetSnapshotInfoType request) throws EucalyptusCloudException {
        GetSnapshotInfoResponseType reply = (GetSnapshotInfoResponseType) request.getReply();
        String snapshotId = request.getKey();
        ArrayList<String> snapshotSet = reply.getSnapshotSet();

        EntityWrapper<WalrusSnapshotInfo> db = new EntityWrapper<WalrusSnapshotInfo>();
        WalrusSnapshotInfo snapshotInfo = new WalrusSnapshotInfo(snapshotId);
        List<WalrusSnapshotInfo> snapshotInfos = db.query(snapshotInfo);

        if(snapshotInfos.size() > 0) {
            WalrusSnapshotInfo foundSnapshotInfo = snapshotInfos.get(0);
            EntityWrapper<WalrusSnapshotSet> dbSet = db.recast(WalrusSnapshotSet.class);
            try {
                String snapshotSetId = foundSnapshotInfo.getSnapshotSetId();
                WalrusSnapshotSet snapshotSetInfo = dbSet.getUnique(new WalrusSnapshotSet(snapshotSetId));
                List<WalrusSnapshotInfo> walrusSnapshotInfos = snapshotSetInfo.getSnapshotSet();
                //bucket name
                reply.setBucket(snapshotSetId);
                //the volume is the snapshot at time 0
                for(WalrusSnapshotInfo walrusSnapshotInfo : walrusSnapshotInfos) {
                    snapshotSet.add(walrusSnapshotInfo.getSnapshotId());
                }
            } catch(Exception ex) {
                db.rollback();
                throw new NoSuchEntityException(snapshotId);
            }
        } else {
            db.rollback();
            throw new NoSuchEntityException(snapshotId);
        }
        db.commit();
        return reply;
    }

    public GetVolumeResponseType GetVolume(GetVolumeType request) throws EucalyptusCloudException {
        GetVolumeResponseType reply = (GetVolumeResponseType) request.getReply();
        if(!enableSnapshots) {
            LOG.warn("Snapshots not enabled. Please check pre-conditions and restart Walrus.");
            return reply;
        }
        String snapshotId = request.getKey();
        String userId = request.getUserId();
        EntityWrapper<WalrusSnapshotInfo> db = new EntityWrapper<WalrusSnapshotInfo>();
        WalrusSnapshotInfo snapshotInfo = new WalrusSnapshotInfo(snapshotId);
        List<WalrusSnapshotInfo> snapshotInfos = db.query(snapshotInfo);

        if(snapshotInfos.size() > 0) {
            WalrusSnapshotInfo foundSnapshotInfo = snapshotInfos.get(0);
            String snapshotSetId = foundSnapshotInfo.getSnapshotSetId();
            EntityWrapper<WalrusSnapshotSet> dbSet = db.recast(WalrusSnapshotSet.class);
            WalrusSnapshotSet snapSet = new WalrusSnapshotSet(snapshotSetId);
            List<WalrusSnapshotSet> snapshotSets = dbSet.query(snapSet);
            if(snapshotSets.size() > 0) {
                WalrusSnapshotSet snapshotSet = snapshotSets.get(0);
                List<WalrusSnapshotInfo> snapshots = snapshotSet.getSnapshotSet();
                ArrayList<String> snapshotIds = new ArrayList<String>();
                ArrayList<String> vgNames = new ArrayList<String>();
                ArrayList<String> lvNames = new ArrayList<String>();
                for(WalrusSnapshotInfo snap : snapshots) {
                    snapshotIds.add(snap.getSnapshotId());
                    vgNames.add(snap.getVgName());
                    lvNames.add(snap.getLvName());
                }
                String volumeKey = storageManager.createVolume(snapshotSetId, snapshotIds, vgNames, lvNames, snapshotId, foundSnapshotInfo.getVgName(), foundSnapshotInfo.getLvName());

                AddObject(userId, snapshotSetId, volumeKey);

                GetObjectType getObjectType = new GetObjectType();
                getObjectType.setUserId(request.getUserId());
                getObjectType.setGetData(true);
                getObjectType.setInlineData(false);
                getObjectType.setGetMetaData(false);
                getObjectType.setBucket(snapshotSetId);
                getObjectType.setKey(volumeKey);
                getObjectType.setIsCompressed(true);
                getObjectType.setDeleteAfterGet(true);
                db.commit();
                GetObjectResponseType getObjectResponse = GetObject(getObjectType);
                reply.setEtag(getObjectResponse.getEtag());
                reply.setLastModified(getObjectResponse.getLastModified());
                reply.setSize(getObjectResponse.getSize());
                request.setRandomKey(getObjectType.getRandomKey());
                request.setBucket(snapshotSetId);
                request.setKey(volumeKey);
                request.setIsCompressed(true);
            } else {
                db.rollback();
                throw new EucalyptusCloudException("Could not find snapshot set");
            }
        } else {
            db.rollback();
            throw new NoSuchSnapshotException(snapshotId);
        }

        return reply;
    }

    public DeleteWalrusSnapshotResponseType DeleteWalrusSnapshot(DeleteWalrusSnapshotType request) throws EucalyptusCloudException {
        DeleteWalrusSnapshotResponseType reply = (DeleteWalrusSnapshotResponseType) request.getReply();
        if(!enableSnapshots) {
            LOG.warn("Snapshots not enabled. Please check pre-conditions and restart Walrus.");
            return reply;
        }
        String snapshotId = request.getKey();

        //Load the entire snapshot tree and then remove the snapshot
        EntityWrapper<WalrusSnapshotInfo> db = new EntityWrapper<WalrusSnapshotInfo>();
        WalrusSnapshotInfo snapshotInfo = new WalrusSnapshotInfo(snapshotId);
        List<WalrusSnapshotInfo> snapshotInfos = db.query(snapshotInfo);

        ArrayList<String> vgNames = new ArrayList<String>();
        ArrayList<String> lvNames = new ArrayList<String>();
        ArrayList<String> snapIdsToDelete = new ArrayList<String>();

        //Delete is idempotent.
        reply.set_return(true);
        if(snapshotInfos.size() > 0) {
            WalrusSnapshotInfo foundSnapshotInfo = snapshotInfos.get(0);
            if(!foundSnapshotInfo.getTransferred()) {
                db.rollback();
                throw new SnapshotInUseException(snapshotId);
            }
            String snapshotSetId = foundSnapshotInfo.getSnapshotSetId();

            EntityWrapper<WalrusSnapshotSet> dbSet = db.recast(WalrusSnapshotSet.class);
            WalrusSnapshotSet snapshotSetInfo = new WalrusSnapshotSet(snapshotSetId);
            List<WalrusSnapshotSet> snapshotSetInfos = dbSet.query(snapshotSetInfo);

            if(snapshotSetInfos.size() > 0) {
                WalrusSnapshotSet foundSnapshotSetInfo = snapshotSetInfos.get(0);
                String bucketName = foundSnapshotSetInfo.getSnapshotSetId();
                List<WalrusSnapshotInfo> snapshotSet = foundSnapshotSetInfo.getSnapshotSet();
                ArrayList<String> snapshotIds = new ArrayList<String>();
                WalrusSnapshotInfo snapshotSetSnapInfo = null;
                for(WalrusSnapshotInfo snapInfo : snapshotSet) {
                    String snapId = snapInfo.getSnapshotId();
                    snapshotIds.add(snapId);
                    if(snapId.equals(foundSnapshotInfo.getSnapshotId())) {
                        snapshotSetSnapInfo = snapInfo;
                    }
                }
                //delete from the database
                db.delete(foundSnapshotInfo);
                vgNames.add(foundSnapshotInfo.getVgName());
                lvNames.add(foundSnapshotInfo.getLvName());
                snapIdsToDelete.add(foundSnapshotInfo.getSnapshotId());
                if(snapshotSetSnapInfo != null) {
                    snapshotSet.remove(snapshotSetSnapInfo);
                    //only 1 entry left? It is the volume
                    if(snapshotSet.size() == 1) {
                        WalrusSnapshotInfo snapZeroInfo = snapshotSet.get(0);
                        if(snapZeroInfo.getSnapshotId().startsWith("vol")) {
                            snapshotSet.remove(snapZeroInfo);
                            dbSet.delete(foundSnapshotSetInfo);
                            snapZeroInfo = new WalrusSnapshotInfo(snapZeroInfo.getSnapshotId());
                            WalrusSnapshotInfo foundVolInfo = db.getUnique(snapZeroInfo);
                            db.delete(foundVolInfo);
                            vgNames.add(foundVolInfo.getVgName());
                            lvNames.add(foundVolInfo.getLvName());
                            snapIdsToDelete.add(foundVolInfo.getSnapshotId());
                        }
                    }
                }
                //remove the snapshot in the background
                SnapshotDeleter snapshotDeleter = new SnapshotDeleter(bucketName, snapIdsToDelete,
                        vgNames, lvNames, snapshotIds);
                snapshotDeleter.start();
            } else {
                db.rollback();
                throw new NoSuchSnapshotException(snapshotId);
            }
        }
        db.commit();
        return reply;
    }


    private class SnapshotDeleter extends Thread {
        private String bucketName;
        private List<String> snapshotSet;
        private List<String> snapshotIdsToDelete;
        private List<String> vgNames;
        private List<String> lvNames;

        public SnapshotDeleter(String bucketName, List<String> snapshotIdsToDelete, List<String> vgNames, List<String> lvNames, List<String> snapshotSet) {
            this.bucketName = bucketName;
            this.snapshotSet = snapshotSet;
            this.snapshotIdsToDelete = snapshotIdsToDelete;
            this.vgNames = vgNames;
            this.lvNames = lvNames;
        }

        public void run() {
            try {
                for(int i = 0; i < vgNames.size(); ++i) {
                    boolean removeVg = false;
                    //last one?
                    if(i == (vgNames.size() - 1))
                        removeVg = true;
                    String snapId = snapshotIdsToDelete.get(i);
                    storageManager.deleteSnapshot(bucketName, snapId, vgNames.get(i), lvNames.get(i), snapshotSet, removeVg);
                    String snapIdToRemove = null;
                    for(String snapsetId : snapshotSet) {
                        if(snapsetId.equals(snapId)) {
                            snapIdToRemove = snapsetId;
                            break;
                        }
                    }
                    if(snapIdToRemove != null)
                        snapshotSet.remove(snapIdToRemove);
                }
            } catch(EucalyptusCloudException ex) {
                LOG.error(ex, ex);
            }
        }
    }

    public UpdateWalrusConfigurationResponseType UpdateWalrusConfiguration(UpdateWalrusConfigurationType request) {
        UpdateWalrusConfigurationResponseType reply = (UpdateWalrusConfigurationResponseType) request.getReply();
        String rootDir = request.getBucketRootDirectory();
        if(rootDir != null)
            storageManager.setRootDirectory(rootDir);

        return reply;
    }
}
