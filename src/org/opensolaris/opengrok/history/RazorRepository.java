/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/* Portions Copyright 2008 Peter Bray */
package org.opensolaris.opengrok.history;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;

/**
 * Adds access to to a Razor Repository
 * 
 * http://www.visible.com/Products/Razor/index.htm
 * 
 * A brief and simplistic overview of Razor
 * 
 * Razor uses the term 'Group' for what might traditionally be called a 
 * repository, that is a collection of files and folders. A collection of
 * Groups is called a 'Universe' in Razor. Razor supports multiple Universes,
 * and these are the units which can be independently started and stopped.
 * 
 * A universe usually consists of on issue tracking data set called "Issues",
 * this is managed from a user perspective by a GUI called 'issues' or a web
 * interface called 'issue weaver'. Each group has a file repository, managed
 * by a GUI called 'versions', and an associated GUI called 'threads' is used
 * to collect together related versions of files into a "Thread", which can 
 * be thought of as a like a tag against the repository, but is managed outside
 * of the file repository itself.
 * 
 * From the point of view of the user, they raise an issue to document a unit
 * of work, make changes to a collection of files against that issue, and then
 * combine one or more issues into a thread to represent a releasable product.
 * Of course, there is more to the product then this brief outline can do
 * justice to but these general concepts should assist in understanding the
 * concepts presented in the OpenGrok Razor Repository interface.
 * 
 * At an implementation level, a Universe consists of it Issues database,
 * and one or more Groups each consisting of file repository and thread
 * repository. Each of these repositories is implemented with a series of
 * directories (Archive, History, Info, Scripts and Tables). When file revision
 * control is needed on a file (both committed files and some internal 
 * implementation files), Razor supports the use of either SCCS or RCS
 * for non-binary files and numbered compressed instances of binary files.
 * Each file is given a unique (per universe) numerical identifier in the 
 * file .../razor_db/<universe>/RAZOR_UNIVERSE/Mapping, this is used by Razor
 * to track files over time, as they renamed or deleted.
 *
 * Unfortunately, the Razor command line interface does not support features
 * that other SCMS support like 'log' and 'annotate'. Also, Razor check-outs
 * leave no indication that the files are from a centralised repository, so
 * it will not be possible to implement this module from a copy or check-out
 * of the repository, we will have to access (in a read-only manner) the actual
 * repository itself, extracting the information directly or via SCCS/RCS
 * interfaces. 
 * 
 * IMPLEMENTATION NOTES:
 *
 * The Razor implementation used for development and testing of this code
 * has the following properties which may affect the success of others
 * trying to use this implementation:
 *   - Multiple Universes
 *   - Each Universe had Issues databases
 *   - Each Universe has multiple Groups, with Threads but no Projects
 *   - The file repository format chosen was the non-default implementation,
 *     that is, RCS rather than the default SCCS implementation
 *   - Binary files are compressed with the standard UNIX 'compress' tool
 *   - Not all Groups would be suitable for OpenGrok analysis
 *   - Use of Razor command line interface was deemed impractical
 *   - The use of the Mapping file and the tracking of renamed and deleted
 *     files was deemed too complex for the first implementation attempt
 *   - The Razor implementation was on a single Sun Solaris SPARC Server
 *   - The code development/testing used NetBeans-6.1 and Sun JDK 6 Update 6
 * 
 * The initial implementation was to create symbolic links in the SRC_ROOT
 * directory to the Razor Group directories you wished OpenGrok to process.
 * The Razor implementation of HistoryParser and DirectoryHistoryParser were
 * functional, but the file analysis infrastructure could not support the
 * virtual filesystem that I was creating in my implementation of the
 * DirectoryHistoryParser for Razor. Essentially I was trying to make a
 * virtual file system, and remap all file names as required, but the file
 * analysis code assumed it could just read actual directories and process
 * their contents. I would have had to implement a VirtualFile and possibly
 * VirtualFilesystem classes, recode the file analysis framework and develop
 * Standard and Razor implementations. THIS APPROACH HAS BEEN ABORTED!!!
 * 
 * The implementation now requires that you checkout a read-only copy of
 * the directories you wish OpenGrok to process, and place in the top-level
 * directory of each, a symlink called ".razor" to the Razor Group directory
 * for that folder. Example: if you have a universe called MyUniverse,
 * containing a group called MyGroup with top-level folders called Documentation
 * and Implementation. Then in SRC_ROOT (or a sub-directory of it), check-out
 * read-only say the Implementation into $SRC_ROOT, and create a symlink called
 * $SRC_ROOT/Implementation/.razor which points to a directory of the form
 * <prefix>/razor_db/<Universe>/RAZOR_UNIVERSE/DOMAIN_01/<GroupName>, so that
 * might be /repository/razor/razor_db/MyUniverse/RAZOR_UNIVERSE/DOMAIN_01/MyGroup
 *  
 * Because of the distributed nature of information storage in Razor (by this
 * I mean, that each file in the repository is represented by files of the 
 * same name (and path) under multiple directories (Archive, History & Info)),
 * I'm continuously mapping SRC_ROOT based names into the appropriate
 * subdirectory of the actual repository. 
 * 
 * For the initial implementation, it is assume that RCS is in use, on a UNIX
 * platform, but I will try not to hard-code too much in relation to these
 * assumptions. Also I have not worked Java for almost 8 years now, so please
 * forgive any oversights in this regard.
 *  
 * @author Peter Bray <Peter.Darren.Bray@gmail.com>
 */
public class RazorRepository extends Repository {

    // The path of the repository itself is stored in the super class.
    // The directory containing our repository directory (usually SRC_ROOT,
    // but if the user is nesting Razor repositories in structured tree...)
    private String opengrokSourceRootDirectoryPath;
    // The base directory of that Razor Group (.razor symlink destination)
    private String razorGroupBaseDirectoryPath;

    public RazorRepository() {
        // Required to restore saved configuration
    }

    @Override
    public void setDirectoryName(String directoryName) {
        super.setDirectoryName(directoryName);
        File opengrokBaseDirectory = new File(directoryName);
        opengrokSourceRootDirectoryPath = opengrokBaseDirectory.getParentFile().getAbsolutePath();
        razorGroupBaseDirectoryPath = new File(directoryName, ".razor").getAbsolutePath();
    }

    public String getOpengrokSourceRootDirectoryPath() {
        return opengrokSourceRootDirectoryPath;
    }

    public void setOpengrokSourceRootDirectoryPath(String opengrokSourceRootDirectoryPath) {
        this.opengrokSourceRootDirectoryPath = opengrokSourceRootDirectoryPath;
    }

    public String getRazorGroupBaseDirectoryPath() {
        return razorGroupBaseDirectoryPath;
    }

    public void setRazorGroupBaseDirectoryPath(String razorGroupBaseDirectoryPath) {
        this.razorGroupBaseDirectoryPath = razorGroupBaseDirectoryPath;
    }

    String getOpenGrokFileNameFor(File file) {
        return file.getAbsolutePath().substring(opengrokSourceRootDirectoryPath.length());
    }

    File getSourceNameForOpenGrokName(String path) {
        return new File(opengrokSourceRootDirectoryPath + path);
    }

    File getRazorHistoryFileFor(File file) throws Exception {
        return pathTranslation(file, "/History/", "");
    }

    File getRazorArchiveRCSFileFor(File file) throws Exception {
        return pathTranslation(file, "/Archive/RZ_VCS/", ",v");
    }

    File getRazorArchiveBinaryFileFor(File file, String rev) throws Exception {
        return pathTranslation(file, "/Archive/BINARY/", "@" + rev + ".Z");
    }

    File getRazorArchiveSCCSFileFor(File file) throws Exception {
        return pathTranslation(file, "/Archive/SCCS/", "");
    }

    @Override
    Class<? extends HistoryParser> getHistoryParser() {
        return RazorHistoryParser.class;
    }

    @Override
    Class<? extends HistoryParser> getDirectoryHistoryParser() {
        return RazorDirectoryHistoryParser.class;
    }

    @Override
    boolean fileHasHistory( File file) {

        // TODO : SCCS & Binary Implementation, Rename & Delete Support

        try {
            File mappedFile = getRazorHistoryFileFor(file);
            return mappedFile.exists() && mappedFile.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    InputStream getHistoryGet( String parent, String basename,
            String rev) {
        // System.err.println("getHistoryGet( " + parent + ", " + basename + ", " + rev + ")");

        // TODO : SCCS & Binary Implementation, Rename & Delete Support

        try {
            File rcsFile = getRazorArchiveRCSFileFor(new File(parent, basename));
            if (rcsFile != null && rcsFile.exists()) {
                String rcsPath = rcsFile.getPath();
                return new BufferedInputStream(new RCSget(rcsPath, rev));
            }
        } catch (Exception e) {
            System.err.println("getHistoryGet( " + parent + ", " + basename + ", " + rev + ")");
            e.printStackTrace();
        }
        return null;
    }

    @Override
    Annotation annotate( File file, String revision)
            throws Exception {
        // System.err.println("annotate( " + file.getPath() + ", " + revision + ")");

        // TODO : SCCS & Binary Implementation, Rename & Delete Support

        File rcsFile = getRazorArchiveRCSFileFor(file);
        if (rcsFile != null && rcsFile.exists()) {
            return RCSRepository.annotate(file, revision, rcsFile);
        }

        return null;
    }

    @Override
    boolean fileHasAnnotation( File file) {

        // TODO : SCCS & Binary Implementation, Rename & Delete Support

        // Assumes we have Blame installed for RCS annotation support

        try {
            File mappedFile = getRazorArchiveRCSFileFor(file);
            return mappedFile.exists() && mappedFile.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    boolean isCacheable() {
        return true;
    }

    @Override
    void update() throws Exception {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    void createCache( HistoryCache cache) throws Exception {

        Class<? extends HistoryParser> dhpClass = getDirectoryHistoryParser();
        Class<? extends HistoryParser> fhpClass = getHistoryParser();

        // If we don't have a directory parser, we can't create the cache
        // this way. Just give up and return.
        if (dhpClass == null) {
            return;
        }

        HistoryParser directoryHistoryParser = dhpClass.newInstance();
        HistoryParser fileHistoryParser = null;

        if (fhpClass != null) {
            fileHistoryParser = fhpClass.newInstance();
        }

        File directory = new File(getDirectoryName());

        History history = directoryHistoryParser.parse(directory, this);

        if (history != null && history.getHistoryEntries() != null) {
            HashMap<String, List<HistoryEntry>> map =
                    new HashMap<String, List<HistoryEntry>>();

            for (HistoryEntry e : history.getHistoryEntries()) {
                for (String s : e.getFiles()) {
                    List<HistoryEntry> list = map.get(s);
                    if (list == null) {
                        if (fileHistoryParser != null) {
                            File file = getSourceNameForOpenGrokName(s);
                            History fileHistory = fileHistoryParser.parse(file, this);
                            if (fileHistory != null) {
                                list = fileHistory.getHistoryEntries();
                            }
                        }
                        if (list == null) {
                            list = new ArrayList<HistoryEntry>();
                        }
                        map.put(s, list);
                    }

                    if (e.getDate() != null) {
                        list.add(e);
                    }
                }
            }

            File root = RuntimeEnvironment.getInstance().getSourceRootFile();
            for (Map.Entry<String, List<HistoryEntry>> e : map.entrySet()) {
                for (HistoryEntry ent : e.getValue()) {
                    ent.strip();
                }
                History hist = new History();
                hist.setHistoryEntries(e.getValue());
                File file = new File(root, e.getKey());
                if (!file.isDirectory()) {
                    cache.store(hist, file);
                }
            }
        }
    }

    private File pathTranslation(File file, String intermediateElements, String suffix) throws Exception {

        if (!file.getAbsolutePath().startsWith(opengrokSourceRootDirectoryPath)) {
            throw new Exception("Invalid Path for Translation '" + file.getPath() + "', '" + intermediateElements + "', '" + suffix + "'");
        }

        String path = razorGroupBaseDirectoryPath + intermediateElements;

        if (file.getAbsolutePath().length() > opengrokSourceRootDirectoryPath.length()) {
            path += file.getAbsolutePath().substring(opengrokSourceRootDirectoryPath.length() + 1);
        }

        path += suffix;

        return new File(path);
    }
    /*
     * The following are left-overs from the original implementation
     */
    private final static Pattern RAZOR_GROUP_PATTERN =
            Pattern.compile("^(.*)/razor_db/(.*)/RAZOR_UNIVERSE/DOMAIN_\\d\\d/(.*)$");

    public static boolean isInRazorGroupTree(File file) {

        try {
            // System.err.println("Razor Repository - Absolute  Path = '" + file.getAbsolutePath() + "'");
            // System.err.println("Razor Repository - Canonical Path = '" + file.getCanonicalPath() + "'");
            if (file.getAbsolutePath().equals(file.getCanonicalPath())) {
                // System.err.println("Non-symlink can't be Razor Repository for '" + file.getAbsolutePath() + "'" );
                return false;
            }

            Matcher matcher = RAZOR_GROUP_PATTERN.matcher(file.getCanonicalPath());
            if (matcher.find()) {
                // String prefix = matcher.group(1);
                // String universe = matcher.group(2);
                // String group = matcher.group(3);
                // System.err.println("Found Razor Universe '" + universe + "' / Group '" + group + "' in '" + prefix + "'");
                return true;
            } else {
                // System.err.println("Non-Razor Repository '" + file.getAbsolutePath() + "'");
                return false;
            }
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    boolean isRepositoryFor(File file) {
        File f = new File(file, ".razor");
        return f.exists() && f.isDirectory();
    }
    
    
}