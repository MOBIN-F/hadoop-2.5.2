/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.util.Time.monotonicNow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.util.Daemon;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * LeaseManager does the lease housekeeping for writing on files.   
 * This class also provides useful static methods for lease recovery.
 * 
 * Lease Recovery Algorithm
 * 1) Namenode retrieves lease information
 * 2) For each file f in the lease, consider the last block b of f
 * 2.1) Get the datanodes which contains b
 * 2.2) Assign one of the datanodes as the primary datanode p

 * 2.3) p obtains a new generation stamp from the namenode
 * 2.4) p gets the block info from each datanode
 * 2.5) p computes the minimum block length
 * 2.6) p updates the datanodes, which have a valid generation stamp,
 *      with the new generation stamp and the minimum block length 
 * 2.7) p acknowledges the namenode the update results

 * 2.8) Namenode updates the BlockInfo
 * 2.9) Namenode removes f from the lease
 *      and removes the lease once all files have been removed
 * 2.10) Namenode commit changes to edit log
 */
@InterfaceAudience.Private
public class LeaseManager {
  public static final Log LOG = LogFactory.getLog(LeaseManager.class);

  private final FSNamesystem fsnamesystem;

  private long softLimit = HdfsConstants.LEASE_SOFTLIMIT_PERIOD;
  private long hardLimit = HdfsConstants.LEASE_HARDLIMIT_PERIOD;

  //
  // Used for handling lock-leases
  // Mapping: leaseHolder -> Lease
  //保存了租约持有者与租约的对应关系
  private final SortedMap<String, Lease> leases = new TreeMap<>();
  // Set of: Lease  使用优先级队列来保存租约
  private final PriorityQueue<Lease> sortedLeases = new PriorityQueue<>(512,
      new Comparator<Lease>() {
        @Override
        public int compare(Lease o1, Lease o2) {
          return Long.signum(o1.getLastUpdate() - o2.getLastUpdate());
        }
  });
  // INodeID -> Lease
  // //保存了文件INode与租约的对应关系
  private final HashMap<Long, Lease> leasesById = new HashMap<>();

  private Daemon lmthread;
  private volatile boolean shouldRunMonitor;

  LeaseManager(FSNamesystem fsnamesystem) {this.fsnamesystem = fsnamesystem;}

  Lease getLease(String holder) {
    return leases.get(holder);
  }

  /**
   * This method iterates through all the leases and counts the number of blocks
   * which are not COMPLETE. The FSNamesystem read lock MUST be held before
   * calling this method.
   */
  synchronized long getNumUnderConstructionBlocks() {
    assert this.fsnamesystem.hasReadLock() : "The FSNamesystem read lock wasn't"
      + "acquired before counting under construction blocks";
    long numUCBlocks = 0;
    for (Long id : getINodeIdWithLeases()) {
      final INodeFile cons = fsnamesystem.getFSDirectory().getInode(id).asFile();
      if (!cons.isUnderConstruction()) {
        LOG.warn("The file " + cons.getFullPathName()
            + " is not under construction but has lease.");
        continue;
      }
      BlockInfo[] blocks = cons.getBlocks();
      if(blocks == null) {
        continue;
      }
      for(BlockInfo b : blocks) {
        if(!b.isComplete())
          numUCBlocks++;
        }
      }
    LOG.info("Number of blocks under construction: " + numUCBlocks);
    return numUCBlocks;
  }

  Collection<Long> getINodeIdWithLeases() {return leasesById.keySet();}

  /** @return the lease containing src */
  public synchronized Lease getLease(INodeFile src) {return leasesById.get(src.getId());}

  /** @return the number of leases currently in the system */
  @VisibleForTesting
  public synchronized int countLease() {
    return sortedLeases.size();
  }

  /** @return the number of paths contained in all leases */
  synchronized long countPath() {
    return leasesById.size();
  }

  /**
   * Adds (or re-adds) the lease for the specified file.
   * @param holder :持有者
   * @param inodeId :文件inode
   */
  /*
  * 当客户端创建文件 和追加文件 时，FSNamesystem.startFileInternal()以及appendFileInternaal()方法
  * 都 会调用LeaseManager.addLease()为该客户端在HDFS文件上添加一个租约，同时Namenode读取fsimage文件时也需要在LeaseManager中添加
  * 租约信息，以及在Namenode读取editlog时也需要在LeaseManager添加租约信息
  * */
  synchronized Lease addLease(String holder, long inodeId) {
    Lease lease = getLease(holder);    //构造租约
    if (lease == null) {
      lease = new Lease(holder);    //构造Lease对象
      leases.put(holder, lease);    //将lease与持有者的对应关系添加到leases中·
      sortedLeases.add(lease);     //将lease对象添加到sortleases中
    } else {
      //如果租约不为空则更新租约时间
      renewLease(lease);
    }
    leasesById.put(inodeId, lease);
    lease.files.add(inodeId);
    return lease;
  }

  /*
  * 1. Namenoce关闭构建中的HDFS文件时，会调用FSNamesystem.finalizeINodeFileUnderConstruction()方法将INode从构建状态转换成非构建状态，
  * 同时由于客户端已经完成了文件的写操作，所以需要从LeaseManager中删除该文件的租约
  * 2. 在进行目录树的删除操作时，对于已经打开的文件，如果客户端从文件系统目录树中移出该HDFS文件，则会调用removerLeaseWithPrefixPath
  * */
  synchronized void removeLease(long inodeId) {
    final Lease lease = leasesById.get(inodeId);
    if (lease != null) {
      removeLease(lease, inodeId);
    }
  }

  /**
   * Remove the specified lease and src.
   */
  private synchronized void removeLease(Lease lease, long inodeId) {
    leasesById.remove(inodeId);
    if (!lease.removeFile(inodeId)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("inode " + inodeId + " not found in lease.files (=" + lease
                + ")");
      }
    }

    if (!lease.hasFiles()) {
      leases.remove(lease.holder);
      if (!sortedLeases.remove(lease)) {
        LOG.error(lease + " not found in sortedLeases");
      }
    }
  }

  /**
   * Remove the lease for the specified holder and src
   */
  synchronized void removeLease(String holder, INodeFile src) {
    Lease lease = getLease(holder);
    if (lease != null) {
      removeLease(lease, src.getId());
    } else {
      LOG.warn("Removing non-existent lease! holder=" + holder +
          " src=" + src.getFullPathName());
    }
  }

  synchronized void removeAllLeases() {
    sortedLeases.clear();
    leasesById.clear();
    leases.clear();
  }

  /**
   * Reassign lease for file src to the new holder.
   */
  synchronized Lease reassignLease(Lease lease, INodeFile src,
                                   String newHolder) {
    assert newHolder != null : "new lease holder is null";
    if (lease != null) {
      removeLease(lease, src.getId());
    }
    return addLease(newHolder, src.getId());
  }

  /**
   * Renew the lease(s) held by the given client
   * 当客户端打开一个文件或者追加写操作时，LeaseManager会保存这个客户端在该文件的租约，
   * 客户端还会启动一个LeaseRenewer定期更新租约，以防止租约过期
   */
  synchronized void renewLease(String holder) {
    renewLease(getLease(holder));
  }

  /**
   *1. 首先从sortedLease字段中先先移除这个租约
   * 2. 更新这个租约的最后时间
   * 3. 重新加入sortedLeases
   * 因为sortLeases是一个以最后更新时间排序集合，所以每次更新租约后，sortLeases中的顺序也需要重新改变
   */

  synchronized void renewLease(Lease lease) {
    if (lease != null) {
      sortedLeases.remove(lease);
      lease.renew();
      sortedLeases.add(lease);
    }
  }

  /**
   * Renew all of the currently open leases.
   */
  synchronized void renewAllLeases() {
    for (Lease l : leases.values()) {
      renewLease(l);
    }
  }

  /************************************************************
   * A Lease governs all the locks held by a single client.
   * For each client there's a corresponding lease, whose
   * timestamp is updated when the client periodically
   * checks in.  If the client dies and allows its lease to
   * expire, all the corresponding locks can be released.
   *************************************************************/
  class Lease {
    private final String holder;  //保存租约持有者的信息
    private long lastUpdate;     //租约的最后更新时间
    private final HashSet<Long> files = new HashSet<>();  //保存文件的Inode
  
    /** Only LeaseManager object can create a lease */
    private Lease(String holder) {
      this.holder = holder;
      renew();
    }
    /** Only LeaseManager object can renew a lease */
    private void renew() {
      this.lastUpdate = monotonicNow();
    }

    /** @return true if the Hard Limit Timer has expired */
    public boolean expiredHardLimit() {
      return monotonicNow() - lastUpdate > hardLimit;
    }

    /** @return true if the Soft Limit Timer has expired */
    public boolean expiredSoftLimit() {
      return monotonicNow() - lastUpdate > softLimit;
    }

    /** Does this lease contain any path? */
    boolean hasFiles() {return !files.isEmpty();}

    boolean removeFile(long inodeId) {
      return files.remove(inodeId);
    }

    @Override
    public String toString() {
      return "[Lease.  Holder: " + holder
          + ", pending creates: " + files.size() + "]";
    }

    @Override
    public int hashCode() {
      return holder.hashCode();
    }
    
    private Collection<Long> getFiles() {
      return Collections.unmodifiableCollection(files);
    }

    String getHolder() {
      return holder;
    }

    @VisibleForTesting
    long getLastUpdate() {
      return lastUpdate;
    }
  }

  public void setLeasePeriod(long softLimit, long hardLimit) {
    this.softLimit = softLimit;
    this.hardLimit = hardLimit; 
  }
  
  /******************************************************
   * Monitor checks for leases that have expired,
   * and disposes of them.
   ******************************************************/
  class Monitor implements Runnable {
    final String name = getClass().getSimpleName();

    /** Check leases periodically. */
    @Override
    public void run() {
      for(; shouldRunMonitor && fsnamesystem.isRunning(); ) {
        boolean needSync = false;
        try {
          fsnamesystem.writeLockInterruptibly();
          try {
            if (!fsnamesystem.isInSafeMode()) {
              needSync = checkLeases();
            }
          } finally {
            fsnamesystem.writeUnlock("leaseManager");
            // lease reassignments should to be sync'ed.
            if (needSync) {
              fsnamesystem.getEditLog().logSync();
            }
          }
  
          Thread.sleep(fsnamesystem.getLeaseRecheckIntervalMs());
        } catch(InterruptedException ie) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(name + " is interrupted", ie);
          }
        } catch(Throwable e) {
          LOG.warn("Unexpected throwable: ", e);
        }
      }
    }
  }

  /** Check the leases beginning from the oldest.
   *  @return true is sync is needed.
   */
  @VisibleForTesting
  synchronized boolean checkLeases() {
    boolean needSync = false;
    assert fsnamesystem.hasWriteLock();

    long start = monotonicNow();

    while(!sortedLeases.isEmpty() && sortedLeases.peek().expiredHardLimit()
      && !isMaxLockHoldToReleaseLease(start)) {
      Lease leaseToCheck = sortedLeases.peek();
      LOG.info(leaseToCheck + " has expired hard limit");

      final List<Long> removing = new ArrayList<>();
      // need to create a copy of the oldest lease files, because
      // internalReleaseLease() removes files corresponding to empty files,
      // i.e. it needs to modify the collection being iterated over
      // causing ConcurrentModificationException
      Collection<Long> files = leaseToCheck.getFiles();
      Long[] leaseINodeIds = files.toArray(new Long[files.size()]);
      FSDirectory fsd = fsnamesystem.getFSDirectory();
      String p = null;
      for(Long id : leaseINodeIds) {
        try {
          INodesInPath iip = INodesInPath.fromINode(fsd.getInode(id));
          p = iip.getPath();
          // Sanity check to make sure the path is correct
          if (!p.startsWith("/")) {
            throw new IOException("Invalid path in the lease " + p);
          }
          boolean completed = fsnamesystem.internalReleaseLease(
              leaseToCheck, p, iip,
              HdfsServerConstants.NAMENODE_LEASE_HOLDER);
          if (LOG.isDebugEnabled()) {
            if (completed) {
              LOG.debug("Lease recovery for inode " + id + " is complete. " +
                            "File closed.");
            } else {
              LOG.debug("Started block recovery " + p + " lease " + leaseToCheck);
            }
          }
          // If a lease recovery happened, we need to sync later.
          if (!needSync && !completed) {
            needSync = true;
          }
        } catch (IOException e) {
          LOG.error("Cannot release the path " + p + " in the lease "
              + leaseToCheck, e);
          removing.add(id);
        }
        if (isMaxLockHoldToReleaseLease(start)) {
          LOG.debug("Breaking out of checkLeases after " +
              fsnamesystem.getMaxLockHoldToReleaseLeaseMs() + "ms.");
          break;
        }
      }

      for(Long id : removing) {
        removeLease(leaseToCheck, id);
      }
    }

    return needSync;
  }


  /** @return true if max lock hold is reached */
  private boolean isMaxLockHoldToReleaseLease(long start) {
    return monotonicNow() - start >
        fsnamesystem.getMaxLockHoldToReleaseLeaseMs();
  }

  @Override
  public synchronized String toString() {
    return getClass().getSimpleName() + "= {"
        + "\n leases=" + leases
        + "\n sortedLeases=" + sortedLeases
        + "\n leasesById=" + leasesById
        + "\n}";
  }

  void startMonitor() {
    Preconditions.checkState(lmthread == null,
        "Lease Monitor already running");
    shouldRunMonitor = true;
    lmthread = new Daemon(new Monitor());
    lmthread.start();
  }
  
  void stopMonitor() {
    if (lmthread != null) {
      shouldRunMonitor = false;
      try {
        lmthread.interrupt();
        lmthread.join(3000);
      } catch (InterruptedException ie) {
        LOG.warn("Encountered exception ", ie);
      }
      lmthread = null;
    }
  }

  /**
   * Trigger the currently-running Lease monitor to re-check
   * its leases immediately. This is for use by unit tests.
   */
  @VisibleForTesting
  public void triggerMonitorCheckNow() {
    Preconditions.checkState(lmthread != null,
        "Lease monitor is not running");
    lmthread.interrupt();
  }

  @VisibleForTesting
  public void runLeaseChecks() {
    checkLeases();
  }

}
