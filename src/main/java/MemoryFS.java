import com.github.lukethompsxn.edufuse.filesystem.FileSystemStub;
import com.github.lukethompsxn.edufuse.struct.*;
import com.github.lukethompsxn.edufuse.util.ErrorCodes;
import com.github.lukethompsxn.edufuse.util.FuseFillDir;
import com.sun.security.auth.module.UnixSystem;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.types.dev_t;
import jnr.ffi.types.mode_t;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import util.MemoryINode;
import util.MemoryINodeTable;
import util.MemoryVisualiser;
import java.io.IOException;
import java.util.Objects;

/**
 * @author Luke Thompson and Dinith Wannigama (dwan609)
 * @since 04.09.19
 */
public class MemoryFS extends FileSystemStub {
    private static final String HELLO_PATH = "/hello";
    private static final String HELLO_STR = "Hello World!\n";

    private MemoryINodeTable iNodeTable = new MemoryINodeTable();
    private MemoryVisualiser visualiser;
    private UnixSystem unix = new UnixSystem();

    // --------------------- MAINTAIN INODE FOR ROOT SEPARATELY???

    @Override
    public Pointer init(Pointer conn) {

        // setup an example file for testing purposes
        MemoryINode iNode = new MemoryINode();
        FileStat stat = new FileStat(Runtime.getSystemRuntime());

        // you will have to add more stat information here eventually
        stat.st_mode.set(FileStat.S_IFREG | 0444 | 0200);
        stat.st_size.set(HELLO_STR.getBytes().length);
        stat.st_nlink.set(1);
        stat.st_ctim.tv_sec.set(System.currentTimeMillis() / 1000);
        stat.st_ctim.tv_nsec.set(System.nanoTime());
        stat.st_mtim.tv_sec.set(System.currentTimeMillis() / 1000);
        stat.st_mtim.tv_nsec.set(System.nanoTime());
        stat.st_atim.tv_sec.set(System.currentTimeMillis() / 1000);
        stat.st_atim.tv_nsec.set(System.nanoTime());

        iNode.setStat(stat);
        iNode.setContent(HELLO_STR.getBytes());
        iNodeTable.updateINode(HELLO_PATH, iNode);

        if (isVisualised()) {
            visualiser = new MemoryVisualiser();
            visualiser.sendINodeTable(iNodeTable);
        }

        return conn;
    }

    @Override
    public int getattr(String path, FileStat stat) {
        int res = 0;

        if (Objects.equals(path, "/")) { // minimal set up for the mount point root
            stat.st_mode.set(FileStat.S_IFDIR | 0755);
            stat.st_nlink.set(2);

        } else if (iNodeTable.containsINode(path)) {
            FileStat savedStat = iNodeTable.getINode(path).getStat();

            // Fill in the stat object with values from the savedStat object of your inode
            stat.st_mode.set(savedStat.st_mode.intValue());
            stat.st_size.set(savedStat.st_size.intValue());
            stat.st_uid.set(unix.getUid());
            stat.st_gid.set(unix.getGid());
            stat.st_nlink.set(savedStat.st_nlink.intValue());
            stat.st_ctim.tv_sec.set(savedStat.st_ctim.tv_sec.get());
            stat.st_ctim.tv_nsec.set(savedStat.st_ctim.tv_nsec.longValue());
            stat.st_mtim.tv_sec.set(savedStat.st_mtim.tv_sec.get());
            stat.st_mtim.tv_nsec.set(savedStat.st_mtim.tv_nsec.longValue());
            stat.st_atim.tv_sec.set(savedStat.st_atim.tv_sec.get());
            stat.st_atim.tv_nsec.set(savedStat.st_atim.tv_nsec.longValue());

        } else {
            res = -ErrorCodes.ENOENT();
        }

        return res;
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filler, @off_t long offset, FuseFileInfo fi) {
        // For each file in the directory call filler.apply.
        // The filler.apply method adds information on the files
        // in the directory, it has the following parameters:
        // buf - a pointer to a buffer for the directory entries
        // name - the file name (with no "/" at the beginning)
        // stbuf - the FileStat information for the file
        // off - just use 0
        filler.apply(buf, ".", null, 0);
        filler.apply(buf, "..", null, 0);

        for (String fp : iNodeTable.entries()) {
            if (fp.startsWith(path) && fp.contains(path)) {
                // Assumes path always ends with "/"
                String file = fp.substring(path.length());
                filler.apply(buf, file, iNodeTable.getINode(fp).getStat(), 0);
            }
        }

        return 0;
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public int flush(String path, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        if (!iNodeTable.containsINode(path)) {
            return -ErrorCodes.ENOENT();
        }
        // you need to extract data from the content field of the inode and place it in
        // the buffer
        // something like:
        // buf.put(0, content, offset, amount);

        // int amount = 0;

        byte[] content = iNodeTable.getINode(path).getContent();
        int contLength = content.length;

        buf.put(0, content, 0, contLength);

        // Update the file access time metadata
        FileStat fs = iNodeTable.getINode(path).getStat();
        fs.st_atim.tv_sec.set(System.currentTimeMillis() / 1000);
        fs.st_atim.tv_nsec.set(System.nanoTime());

        if (isVisualised()) {
            visualiser.sendINodeTable(iNodeTable);
        }

        return (int) size;
    }

    @Override
    public int write(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        if (!iNodeTable.containsINode(path)) {
            return -ErrorCodes.ENOENT(); // ENONET();
        }

        // Get user-written content
        byte[] dst = new byte[(int) size];
        buf.get(0, dst, 0, (int) size);

        byte[] oldContent = iNodeTable.getINode(path).getContent();

        // Concatenate the new content
        byte[] newContent = new byte[(int) offset + (int) size];
        System.arraycopy(oldContent, 0, newContent, 0, (int) offset);
        System.arraycopy(dst, 0, newContent, (int) offset, (int) size);

        iNodeTable.getINode(path).setContent(newContent);

        // Set the file size and modified time metadata
        FileStat fs = iNodeTable.getINode(path).getStat();
        fs.st_size.set(size + offset);
        fs.st_mtim.tv_sec.set(System.currentTimeMillis() / 1000);
        fs.st_mtim.tv_nsec.set(System.nanoTime());

        if (isVisualised()) {
            visualiser.sendINodeTable(iNodeTable);
        }

        return (int) size;
    }

    @Override
    public int mknod(String path, @mode_t long mode, @dev_t long rdev) {
        if (iNodeTable.containsINode(path)) {
            return -ErrorCodes.EEXIST();
        }

        MemoryINode mockINode = new MemoryINode();
        // set up the stat information for this inode

        FileStat stat = new FileStat(Runtime.getSystemRuntime());

        // you will have to add more stat information here eventually
        stat.st_mode.set(mode);
        stat.st_rdev.set(rdev);
        stat.st_size.set(0);
        stat.st_nlink.set(1);
        stat.st_ctim.tv_sec.set(System.currentTimeMillis() / 1000);
        stat.st_ctim.tv_nsec.set(System.nanoTime());
        // stat.st_mtim.tv_sec.set(System.currentTimeMillis() / 1000);
        // stat.st_mtim.tv_nsec.set(System.nanoTime());
        // stat.st_atim.tv_sec.set(System.currentTimeMillis() / 1000);
        // stat.st_atim.tv_nsec.set(System.nanoTime());

        mockINode.setStat(stat);
        iNodeTable.updateINode(path, mockINode);

        if (isVisualised()) {
            visualiser.sendINodeTable(iNodeTable);
        }

        return 0;
    }

    @Override
    public int statfs(String path, Statvfs stbuf) {
        return super.statfs(path, stbuf);
    }

    @Override
    public int utimens(String path, Timespec[] timespec) {
        // The Timespec array has the following information.
        // You need to set the corresponding fields of the inode's stat object.
        // You can access the data in the Timespec objects with "get()" and
        // "longValue()".
        // You have to find out which time fields these correspond to.

        // timespec[0].tv_nsec - Last access time (atim) - nanoseconds (0-999999999)
        // timespec[0].tv_sec - Last access time (atim) - seconds (>= 0)
        // timespec[1].tv_nsec - Last modification time (mtim) - nanoseconds
        // (0-999999999)
        // timespec[1].tv_sec - Last modification time (mtim) - seconds (>= 0)

        FileStat stat = iNodeTable.getINode(path).getStat();

        System.out.println("\n atim nano - " + timespec[0].tv_nsec.longValue() + "\n");
        System.out.println("\n atim sec - " + timespec[0].tv_sec.longValue() + "\n");
        System.out.println("\n mtim nano - " + timespec[1].tv_nsec.longValue() + "\n");
        System.out.println("\n mtim sec - " + timespec[1].tv_sec.longValue() + "\n");

        stat.st_atim.tv_nsec.set(timespec[0].tv_nsec.longValue());
        stat.st_atim.tv_sec.set(timespec[0].tv_sec.longValue());
        stat.st_mtim.tv_nsec.set(timespec[1].tv_nsec.longValue());
        stat.st_mtim.tv_sec.set(timespec[1].tv_sec.longValue());

        System.out.println("BANANA");

        return 0;
    }

    @Override
    public int link(java.lang.String oldpath, java.lang.String newpath) {
        // Return error if the newpath already exists
        if (!iNodeTable.containsINode(oldpath) || iNodeTable.containsINode(newpath)) {
            return -ErrorCodes.ENONET();
        }

        mknod(newpath, FileStat.S_IFREG | 0444 | 0200, 0); // ----------- UNSURE

        MemoryINode oldfile = iNodeTable.getINode(oldpath);
        oldfile.getStat().st_nlink.set(oldfile.getStat().st_nlink.intValue() + 1);
        iNodeTable.updateINode(newpath, oldfile);

        // FileStat oldfileStat = oldfile.getStat();
        // oldfileStat.st_nlink.set(oldfile.getStat().st_nlink.intValue() + 1);

        // MemoryINode newfile = iNodeTable.getINode(newpath);
        // FileStat newfileStat = newfile.getStat();
        // newfileStat.st_size.set(oldfileStat.st_size.intValue());
        // newfileStat.st_mtim.tv_sec.set(System.currentTimeMillis() / 1000);
        // newfileStat.st_mtim.tv_nsec.set(System.nanoTime());
        // newfileStat.st_atim.tv_sec.set(System.currentTimeMillis() / 1000);
        // newfileStat.st_atim.tv_nsec.set(System.nanoTime());
        // newfile.setContent(oldfile.getContent());

        return 0;
    }

    @Override
    public int unlink(String path) {
        if (!iNodeTable.containsINode(path)) {
            return -ErrorCodes.ENONET();
        }

        FileStat stat = iNodeTable.getINode(path).getStat();
        int numLinks = stat.st_nlink.intValue();

        // Delete the file if there are no more hard links
        if (numLinks > 1) {
            FileStat fs = iNodeTable.getINode(path).getStat();
            fs.st_nlink.set(numLinks - 1);
        }

        iNodeTable.removeINode(path);

        return 0;
    }

    @Override
    public int mkdir(String path, long mode) {
        return 0;
    }

    @Override
    public int rmdir(String path) {
        return 0;
    }

    @Override
    public int rename(String oldpath, String newpath) {
        return 0;
    }

    @Override
    public int truncate(String path, @size_t long size) {
        return 0;
    }

    @Override
    public int release(String path, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public int fsync(String path, int isdatasync, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public int setxattr(String path, String name, Pointer value, @size_t long size, int flags) {
        return 0;
    }

    @Override
    public int getxattr(String path, String name, Pointer value, @size_t long size) {
        return 0;
    }

    @Override
    public int listxattr(String path, Pointer list, @size_t long size) {
        return 0;
    }

    @Override
    public int removexattr(String path, String name) {
        return 0;
    }

    @Override
    public int opendir(String path, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public int releasedir(String path, FuseFileInfo fi) {
        return 0;
    }

    @Override
    public void destroy(Pointer initResult) {
        if (isVisualised()) {
            try {
                visualiser.stopConnection();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int access(String path, int mask) {
        return 0;
    }

    @Override
    public int lock(String path, FuseFileInfo fi, int cmd, Flock flock) {
        return 0;
    }

    public static void main(String[] args) {
        MemoryFS fs = new MemoryFS();
        try {
            fs.mount(args, true);
        } finally {
            fs.unmount();
        }
    }
}