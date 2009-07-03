/**
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author
 * tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package openr66.authentication;

import java.io.File;

import openr66.filesystem.R66Dir;
import openr66.filesystem.R66Session;
import openr66.protocol.config.Configuration;
import goldengate.common.command.NextCommandReply;
import goldengate.common.command.exception.Reply421Exception;
import goldengate.common.command.exception.Reply502Exception;
import goldengate.common.command.exception.Reply530Exception;
import goldengate.common.file.filesystembased.FilesystemBasedAuthImpl;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;

/**
 * @author frederic bregier
 *
 */
public class R66Auth extends FilesystemBasedAuthImpl {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(R66Auth.class);
    /**
     * Current authentication
     */
    private R66SimpleAuth currentAuth = null;
    
    /**
     * @param session
     */
    public R66Auth(R66Session session) {
        super(session);
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#businessClean()
     */
    @Override
    protected void businessClean() {
        currentAuth = null;        
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#getBaseDirectory()
     */
    @Override
    protected String getBaseDirectory() {
        return Configuration.baseDirectory;
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#setBusinessAccount(java.lang.String)
     */
    @Override
    protected NextCommandReply setBusinessAccount(String arg0)
            throws Reply421Exception, Reply530Exception, Reply502Exception {
        return null;
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#setBusinessPassword(java.lang.String)
     */
    @Override
    protected NextCommandReply setBusinessPassword(String arg0)
            throws Reply421Exception, Reply530Exception {
        return null;
    }
    
    public boolean connection(String hostId, byte [] arg0) throws Reply530Exception, Reply421Exception {
        R66SimpleAuth auth = Configuration.configuration.fileBasedConfiguration.getSimpleAuth(user);
        if (auth == null) {
            setIsIdentified(false);
            currentAuth = null;
            throw new Reply530Exception("HostId not allowed");
        }
        currentAuth = auth;
        if (currentAuth == null) {
            setIsIdentified(false);
            throw new Reply530Exception("Needs a correct HostId");
        }
        if (currentAuth.isKeyValid(arg0)) {
            this.user = hostId;
            setRootFromAuth();
            getSession().getDir().initAfterIdentification();
            return true;
        }
        throw new Reply530Exception("Key is not valid for this HostId");
    }
    /**
     * Set the root relative Path from current status of Authentication (should
     * be the highest level for the current authentication). If
     * setBusinessRootFromAuth returns null, by default set /user.
     *
     * @exception Reply421Exception
     *                if the business root is not available
     */
    private void setRootFromAuth() throws Reply421Exception {
        rootFromAuth = setBusinessRootFromAuth();
        if (rootFromAuth == null) {
            rootFromAuth = R66Dir.SEPARATOR + user;
        }
    }
    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#setBusinessRootFromAuth()
     */
    @Override
    protected String setBusinessRootFromAuth() throws Reply421Exception {
        String path = null;
        path = R66Dir.SEPARATOR + user;
        String fullpath = getAbsolutePath(path);
        File file = new File(fullpath);
        if (!file.isDirectory()) {
            throw new Reply421Exception("Filesystem not ready");
        }
        return path;
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.filesystembased.FilesystemBasedAuthImpl#setBusinessUser(java.lang.String)
     */
    @Override
    protected NextCommandReply setBusinessUser(String arg0)
            throws Reply421Exception, Reply530Exception {
        return null;
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.AuthInterface#isAdmin()
     */
    @Override
    public boolean isAdmin() {
        return currentAuth.isAdmin;
    }

    /* (non-Javadoc)
     * @see goldengate.common.file.AuthInterface#isBusinessPathValid(java.lang.String)
     */
    @Override
    public boolean isBusinessPathValid(String newPath) {
        if (newPath == null) {
            return false;
        }
        return newPath.startsWith(getBusinessPath());
    }

}
