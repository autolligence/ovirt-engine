#!/usr/bin/python

# Imports
import sys
import os
import shutil
import logging
import traceback
import types
import pwd
from optparse import OptionParser
import yum
import common_utils as utils
import basedefs

# Consts
#TODO: Work with a real list here
RPM_LIST = """
ovirt-engine
ovirt-engine-backend
ovirt-engine-config
ovirt-engine-dbscripts
ovirt-engine-genericapi
ovirt-engine-notification-service
ovirt-engine-restapi
ovirt-engine-setup
ovirt-engine-tools-common
ovirt-engine-userportal
ovirt-engine-webadmin-portal
ovirt-image-uploader
ovirt-iso-uploader
ovirt-log-collector
vdsm-bootstrap
"""

RPM_BACKEND = "ovirt-engine-backend"
RPM_DBSCRIPTS = "ovirt-engine-dbscripts"
RPM_SETUP = "ovirt-engine-setup"
RPM_UPGRADE = "engine-upgrade"

# DB default configuration
SERVER_NAME = basedefs.DB_HOST
SERVER_PORT = basedefs.DB_PORT
SERVER_ADMIN = basedefs.DB_ADMIN


# CONST
BACKUP_DIR = "/var/lib/ovirt-engine/backups"
BACKUP_FILE = "ovirt-engine_db_backup"
LOG_PATH = "/var/log/ovirt-engine"
LOG_FILE = "ovirt-engine-upgrade.log"

ETL_SERVICE="/etc/init.d/ovirt-engine-etl"

# Versions
UNSUPPORTED_VERSION = "2.2"

#MSGS
MSG_ERROR_USER_NOT_ROOT = "Error: insufficient permissions for user %s, you must run with user root."
MSG_NO_ROLLBACK = "Error: Current installation "
MSG_RC_ERROR = "Return Code is not zero"
MSG_INFO_NO_UPGRADE_AVAIL = "No updates available"
MSG_INFO_UPGRADE_AVAIL = "%d Updates available:"
MSG_ERROR_INCOMPATIBLE_UPGRADE = "\nError: a data center or cluster version %s were found on the system.\n\
Such upgrade flow is not supported. Upgrade all %s data centers and clusters and rerun the upgrade utility.\n" \
% (UNSUPPORTED_VERSION, UNSUPPORTED_VERSION)
MSG_ERROR_NO_ROLLBACK_AVAIL = "Error: Installed packages are missing from the yum repositories\n\
Please check your yum repositories or use --no-yum-rollback"
MSG_ERROR_NEW_SETUP_AVAIL="\nError: New %s rpm available via yum.\n\
Please execute `yum update %s`, then re-execute '%s'.\n\
To use the current %s rpm, execute '%s --force-current-setup-rpm'." % (RPM_SETUP, RPM_SETUP, RPM_UPGRADE, RPM_SETUP, RPM_UPGRADE)
MSG_ERROR_BACKUP_DB = "Error: Database backup failed"
MSG_ERROR_RESTORE_DB = "Error: Database restore failed"
MSG_ERROR_DROP_DB = "Error: Database drop failed"
MSG_ERROR_UPDATE_DB = "Error: Database update failed"
MSG_ERROR_RENAME_DB = "Error: Database rename failed. Check that there are no active connections to the DB and try again."
MSG_ERROR_YUM_HISTORY_LIST = "Error: Can't get history from yum"
MSG_ERROR_YUM_HISTORY_GETLAST = "Error: Can't find last install transaction in yum"
MSG_ERROR_YUM_HISTORY_UNDO = "Error: Can't rollback yum"
MSG_ERROR_YUM_LOCK = "Error: Can't edit yum lock file"
MSG_ERROR_RPM_QUERY = "Error: Unable to retrieve rpm versions"
MSG_ERROR_YUM_UPDATE = "Error: Yum update failed"
MSG_ERROR_CHECK_LOG = "Error: Upgrade failed.\nplease check log at %s"
MSG_ERROR_CONNECT_DB = "Error: Failed to connect to database"
MSG_ERR_FAILED_START_ENGINE_SERVICE = "Error: Can't start ovirt-engine"
MSG_ERR_FAILED_ENGINE_SERVICE_STILL_RUN = "Error: Can't stop ovirt-engine service. Please shut it down manually."
MSG_ERR_FAILED_STOP_ENGINE_SERVICE = "Error: Can't stop ovirt-engine"
MSG_ERR_FAILED_STATUS_ENGINE_SERVICE = "Error: Can't get ovirt-engine service status"
MSG_ERR_FAILED_START_SERVICE = "Error: Can't start the %s service"
MSG_ERR_FAILED_STOP_SERVICE = "Error: Can't stop the %s service"
MSG_ERR_SQL_CODE = "Failed running sql query"
MSG_ERR_EXP_UPD_DC_TYPE="Failed updating default Data Center Storage Type in %s db"
MSG_ERROR_ENGINE_PID = "Error: ovirt-engine service is dead, but pid file exists"
MSG_ERROR_YUM_TID = "Error: Yum transaction mismatch"
MSG_ERROR_PGPASS = "Error: DB password file was not found on this system. Verify \
that this system was previously installed and that there's a password file at %s or %s" % \
(basedefs.DB_PASS_FILE, basedefs.ORIG_PASS_FILE)

MSG_INFO_DONE = "DONE"
MSG_INFO_ERROR = "ERROR"
MSG_INFO_REASON = " **Reason: %s**\n"
MSG_INFO_STOP_ENGINE = "Stopping ovirt-engine Service"
MSG_INFO_BACKUP_DB = "Backing Up Database"
MSG_INFO_RENAME_DB = "Rename Database"
MSG_INFO_RESTORE_DB = "Restore Database name"
MSG_INFO_YUM_UPDATE = "Updating rpms"
MSG_INFO_DB_UPDATE = "Updating Database"
MSG_INFO_RUN_POST = "Running post install configuration"
MSG_ERROR_UPGRADE = "\n **Error: Upgrade failed, rolling back**"
MSG_INFO_DB_RESTORE = "Restoring Database"
MSG_INFO_YUM_ROLLBACK = "Rolling back rpms"
MSG_INFO_NO_YUM_ROLLBACK = "Skipping yum rollback"
MSG_INFO_START_ENGINE = "Starting ovirt-engine"
MSG_INFO_DB_BACKUP_FILE = "DB Backup available at "
MSG_INFO_LOG_FILE = "Upgrade log available at"
MSG_INFO_CHECK_UPDATE = "Checking for updates... (This may take several minutes)"
MSG_INFO_UPGRADE_OK = "%s upgrade completed successfully!" % basedefs.APP_NAME
MSG_INFO_STOP_INSTALL_EXIT="Upgrade stopped, Goodbye."
MSG_INFO_UPDATE_ENGINE_PROFILE="Updating ovirt-engine Profile"

MSG_ALERT_STOP_ENGINE="\nDuring the upgrade process, %s  will not be accessible.\n\
All existing running virtual machines will continue but you will not be able to\n\
start or stop any new virtual machines during the process.\n" % basedefs.APP_NAME
INFO_Q_PROCEED="Would you like to proceed"
MSG_INFO_REPORTS="Perform the following steps to upgrade the history service \
or the reporting package:\n\
1. Execute: yum update ovirt-engine-reports*\n\
2. Execute: ovirt-engine-dwh-setup\n\
3. Execute: ovirt-engine-reports-setup"

messages = []

# Code
def getOptions():
    parser = OptionParser()
    parser.add_option("-r", "--no-yum-rollback",
                      action="store_false", dest="yum_rollback", default=True,
                      help="don't rollback yum transaction")

    parser.add_option("-u", "--unattended",
                      action="store_true", dest="unattended_upgrade", default=False,
                      help="unattended upgrade (this option will stop ovirt-engine service before upgrading)")

    parser.add_option("-s", "--force-current-setup-rpm",
                      action="store_true", dest="force_current_setup_rpm", default=False,
                      help="Ignore new %s rpm"%(RPM_SETUP))

    parser.add_option("-c", "--check-update",
                      action="store_true", dest="check_update", default=False,
                      help="Check for available package updates")

    (options, args) = parser.parse_args()
    return (options, args)

def checkEngine(service=basedefs.ENGINE_SERVICE_NAME):
    """
    Ask user to stop ovirt-engine service before
    upgrading ovirt-engine

    returns: true if user choose to stop ovirt-engine
    false otherwise
    """
    logging.debug("checking the status of ovirt-engine service")
    cmd = [
        basedefs.EXEC_SERVICE, service , "status",
    ]
    output, rc = utils.execCmd(cmdList=cmd, failOnError=False)

    if rc == 0:
        logging.debug("ovirt-engine service is up and running")

        print MSG_ALERT_STOP_ENGINE
        answer = utils.askYesNo(INFO_Q_PROCEED)

        # If user choose yes -> return true (stop ovirt-engine)
        if answer:
            return True
        else:
            logging.debug("User chose not to stop ovirt-engine")
            return False

    elif rc == 1:
        # Proc is dead, pid exists
        raise Exception(MSG_ERROR_ENGINE_PID)

    elif rc == 3:
        # If ovirt-engine is not running, we don't need to stop it
        return True

    else:
        raise Exception(MSG_ERR_FAILED_STATUS_ENGINE_SERVICE)

def initLogging():
    global LOG_FILE
    try:
        if not os.path.isdir(LOG_PATH):
            os.makedirs(LOG_PATH)
        LOG_FILE = "%s/ovirt-engine-upgrade_%s.log"%(LOG_PATH, utils.getCurrentDateTime())
        level = logging.DEBUG
        # TODO: Move to mode="a"?
        hdlr = logging.FileHandler(filename = LOG_FILE, mode='w')
        fmts='%(asctime)s::%(levelname)s::%(module)s::%(lineno)d::%(name)s:: %(message)s'
        dfmt='%Y-%m-%d %H:%M:%S'
        fmt = logging.Formatter(fmts, dfmt)
        hdlr.setFormatter(fmt)
        logging.root.addHandler(hdlr)
        logging.root.setLevel(level)
    except:
        logging.error(traceback.format_exc())
        raise Exception("Failed to initiate logger")

def _verifyUserPermissions():
    if (os.geteuid() != 0):
        username = pwd.getpwuid(os.getuid())[0]
        print MSG_ERROR_USER_NOT_ROOT%(username)
        sys.exit(1)

class MYum():
    def __init__(self):
        self.updated = False
        self.yumbase = None
        self.upackages = []
        self.ipackages = []
        self.__initbase()
        self.tid = None

    def __initbase(self):
        self.yumbase = yum.YumBase()
        self.yumbase.preconf.disabled_plugins = ['versionlock']
        self.yumbase.conf.cache = False # Do not relay on existing cache
        self.yumbase.cleanMetadata()
        self.yumbase.cleanSqlite()

    def _validateRpmLockList(self):
        rpmLockList = []
        for rpmName in basedefs.RPM_LOCK_LIST.split():
            cmd = [
                basedefs.EXEC_RPM, "-q", rpmName,
            ]
            output, rc = utils.execCmd(cmdList=cmd)
            if rc == 0:
                rpmLockList.append(rpmName)

        return rpmLockList

    def _lock(self):
        logging.debug("Yum lock started")

        # Create RPM lock list
        cmd = [
            basedefs.EXEC_RPM, "-q",
        ] + self._validateRpmLockList()
        output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_YUM_LOCK)
        with open(basedefs.FILE_YUM_VERSION_LOCK, 'a') as yumlock:
            yumlock.write(output)
        logging.debug("Yum lock completed successfully")

    def _unlock(self):
        logging.debug("Yum unlock started")
        # Read file content
        fd = file(basedefs.FILE_YUM_VERSION_LOCK)
        fileText = fd.readlines()
        fd.close()

        # Change content:
        fd = file(basedefs.FILE_YUM_VERSION_LOCK, 'w')
        for line in fileText:
            if not basedefs.ENGINE_RPM_NAME in line:
                fd.write(line)
        fd.close()
        logging.debug("Yum unlock completed successfully")

    def update(self):
        self.tid = self.getLatestTid(False)
        self._unlock()
        try:
            # yum update ovirt-engine
            # TODO: Run test transaction
            logging.debug("Yum update started")
            cmd = [
                basedefs.EXEC_YUM, "update", "-q", "-y",
            ] + RPM_LIST.split()
            output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_YUM_UPDATE)
            logging.debug("Yum update completed successfully")
        finally:
            self._lock()

    def updateAvailable(self):
        logging.debug("Yum list updates started")

        # Get packages info from yum
        rpms = RPM_LIST.split()
        logging.debug("Getting list of packages to upgrade")
        pkgs = self.yumbase.doPackageLists(patterns=rpms)
        upkgs = self.yumbase.doPackageLists(pkgnarrow="updates", patterns=rpms)

        # Save update candidates
        if upkgs.updates:
            self.upackages = [str(i) for i in sorted(upkgs.updates)] # list of rpm names to update
            logging.debug("%s Packages marked for update:"%(len(self.upackages)))
            logging.debug(self.upackages)
        else:
            logging.debug("No packages marked for update")

        # Save installed packages
        self.ipackages = [str(i) for i in sorted(pkgs.installed)] # list of rpm names already installed
        logging.debug("Installed packages:")
        logging.debug(self.ipackages)

        logging.debug("Yum list updated completed successfully")


        # Return
        if upkgs.updates:
            return True
        else:
            return False

    def packageAvailable(self, pkg):
        pkglist = self.yumbase.doPackageLists(patterns=[pkg]).available
        return len(pkglist) > 0

    def packageInstalled(self, pkg):
        pkglist = self.yumbase.doPackageLists(patterns=[pkg]).installed
        return len(pkglist) > 0

    def depListForRemoval(self, pkgs):

        deplist = []

        # Create list of all packages to remove
        pkgs = self.yumbase.doPackageLists(patterns=pkgs).installed
        for pkg in pkgs:
            self.yumbase.remove(name=pkg.name)

        # Resolve dependencies for removing packages
        self.yumbase.resolveDeps()

        # Create a list of deps packages
        for pkg in self.yumbase.tsInfo.getMembers():
            if pkg.isDep:
                deplist.append(pkg.name)

        # Clear transactions from the 'self' object
        self.yumbase.closeRpmDB()

        # Return the list of dependencies
        return deplist

    def rollbackAvailable(self):
        logging.debug("Yum rollback-avail started")

        # Get All available packages in yum
        rpms = RPM_LIST.split()
        pkgs = self.yumbase.pkgSack.returnPackages(patterns=rpms)
        available = [str(i) for i in sorted(pkgs)] # list of available rpm names
        logging.debug("%s Packages available in yum:"%(len(available)))
        logging.debug(available)

        # Verify all installed packages available in yum
        # self.ipackages is populated in updateAvailable
        for installed in self.ipackages:
            if installed not in available:
                logging.debug("%s not available in yum"%(installed))
                return False

        logging.debug("Yum rollback-avail completed successfully")
        return True

    def rollback(self):
        upgradeTid = self.getLatestTid(True)
        if int(upgradeTid) <= int(self.tid):
            logging.error("Mismatch in yum TID, target TID (%s) is not higher than %s" %(upgradeTid, self.tid))
            raise Exception(MSG_ERROR_YUM_TID)

        if self.updated:
            self._unlock()
            try:
                # yum history undo 17
                # Do rollback only if update went well
                logging.debug("Yum rollback started")
                cmd = [
                    basedefs.EXEC_YUM, "history", "-y", "undo", upgradeTid,
                ]
                output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_YUM_HISTORY_UNDO)
                logging.debug("Yum rollback completed successfully")
            finally:
                self._lock()
        else:
            logging.debug("No rollback needed")

    def getLatestTid(self, updateOnly=False):
        logging.debug("Yum getLatestTid started")
        tid = None

        # Get the list
        cmd = [
            basedefs.EXEC_YUM, "history", "list", basedefs.ENGINE_RPM_NAME,
        ]
        output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_YUM_HISTORY_LIST)

        # Parse last tid
        for line in output.splitlines():
            lsplit = line.split("|")
            if len(lsplit) > 3:
                if updateOnly:
                    if 'Update' in lsplit[3].split() or "U" in lsplit[3].split():
                        tid = lsplit[0].strip()
                        break
                else:
                    if "Action" not in lsplit[3]: # Don't get header of output
                        tid = lsplit[0].strip()
                        break
        if tid is None:
            raise ValueError(MSG_ERROR_YUM_HISTORY_GETLAST)

        logging.debug("Found TID: %s" %(tid))
        logging.debug("Yum getLatestTid completed successfully")
        return tid

    def isCandidateForUpdate(self, rpm):
        candidate = False
        for package in self.upackages:
            if rpm in package:
                candidate = True
        return candidate

    def getUpdateCandidates(self):
        return self.upackages

class DB():
    def __init__(self):
        date = utils.getCurrentDateTime()
        self.sqlfile = "%s/%s_%s.sql" % (BACKUP_DIR, BACKUP_FILE, date)
        self.updated = False
        self.dbrenamed = False
        self.name = basedefs.DB_NAME

    def __del__(self):
        if self.updated:
            logging.debug(MSG_INFO_DB_BACKUP_FILE + self.sqlfile)
            print "* %s %s" % (MSG_INFO_DB_BACKUP_FILE, self.sqlfile)

    def backup(self):
        # pg_dump -C -E UTF8  --column-inserts --disable-dollar-quoting  --disable-triggers -U postgres -h host -p port --format=p -f $dir/$file  ovirt-engine
        logging.debug("DB Backup started")
        #cmd = "%s -C -E UTF8 --column-inserts --disable-dollar-quoting  --disable-triggers -U %s -h %s -p %s --format=p -f %s %s"\
            #%(basedefs.EXEC_PGDUMP, SERVER_ADMIN, SERVER_HOST, SERVER_PORT, self.sqlfile, basedefs.DB_NAME)
        cmd = [
            basedefs.EXEC_PGDUMP,
            "-C", "-E", "UTF8",
            "--column-inserts",
            "--disable-dollar-quoting",
            "--disable-triggers",
            "-U", SERVER_ADMIN,
            "-h", SERVER_NAME,
            "-p", SERVER_PORT,
            "--format=p",
            "-f", self.sqlfile,
            basedefs.DB_NAME,
        ]
        output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_BACKUP_DB)
        logging.debug("DB Backup completed successfully")

    def restore(self):
        # run psql -U engine -h host -p port -d template1 -f <backup directory>/<backup_file>
        # If DB was renamed, restore it

        if self.updated or self.dbrenamed:
            logging.debug("DB Restore started")

            # If we're here, upgrade failed. Drop temp DB.
            cmd = [
                basedefs.EXEC_DROPDB,
                "-U", SERVER_ADMIN,
                "-h", SERVER_NAME,
                "-p", SERVER_PORT,
                self.name,
            ]
            output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_DROP_DB)

            # .pgpass definition
            env = { "PGPASSFILE" : basedefs.DB_PASS_FILE }

            # Restore
            cmd = [
                basedefs.EXEC_PSQL,
                "-U", SERVER_ADMIN,
                "-h", SERVER_NAME,
                "-p", SERVER_PORT,
                "-d", basedefs.DB_TEMPLATE,
                "-f", self.sqlfile,
            ]
            output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_RESTORE_DB, envDict=env)
            logging.debug("DB Restore completed successfully")
        else:
            logging.debug("No DB Restore needed")

    def update(self):
        cwd = os.getcwd()
        os.chdir(basedefs.DIR_DB_SCRIPTS)

        # Make sure we always returning to cwd
        try:
            self.updated = True
            logging.debug("DB Update started")

            # Perform the upgrade
            # ./upgrade.sh -s ${SERVERNAME} -p ${PORT} -u ${USERNAME} -d ${DATABASE};
            dbupgrade = os.path.join(basedefs.DIR_DB_SCRIPTS, basedefs.FILE_DB_UPGRADE_SCRIPT)
            cmd = [
                dbupgrade,
                "-s", SERVER_NAME,
                "-p", SERVER_PORT,
                "-u", SERVER_ADMIN,
                "-d", self.name,
            ]
            output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERROR_UPDATE_DB)
            logging.debug("DB Update completed successfully")

        finally:
            os.chdir(cwd)

    def rename(self, newname):
        """ Rename DB from current name to a newname"""

        # Check that newname is different from current
        if self.name == newname:
            return

        # run the rename query and raise Exception on error
        query = "ALTER DATABASE %s RENAME TO %s" % (self.name, newname)
        try:
            utils.execRemoteSqlCommand(SERVER_ADMIN, SERVER_NAME, SERVER_PORT, basedefs.DB_TEMPLATE, query, True, MSG_ERROR_RENAME_DB)
            # set name to the newname
            self.name = newname
            # toggle dbrenamed value to TRUE
            self.dbrenamed = True
        except:
            # if this happened before DB update, remove DB backup file.
            if not self.updated and os.path.exists(self.sqlfile):
                os.remove(self.sqlfile)
            raise

def stopEngine(service=basedefs.ENGINE_SERVICE_NAME):
    logging.debug("stopping %s service.", service)
    cmd = [
        basedefs.EXEC_SERVICE, service, "stop",
    ]
    output, rc = utils. execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERR_FAILED_STOP_ENGINE_SERVICE)

def startEngine():
    logging.debug("starting %s service.", basedefs.ENGINE_SERVICE_NAME)
    cmd = [
        basedefs.EXEC_SERVICE, basedefs.ENGINE_SERVICE_NAME, "start",
    ]
    output, rc = utils.execCmd(cmdList=cmd, failOnError=True, msg=MSG_ERR_FAILED_START_ENGINE_SERVICE)

def runPost():
    logging.debug("Running post script")
    import post_upgrade as post
    post.run()
    logging.debug("Post script completed successfully")

def runFunc(funcList, dispString):
    print "%s..."%(dispString),
    sys.stdout.flush()
    spaceLen = basedefs.SPACE_LEN - len(dispString)
    try:
        for func in funcList:
            if type(func) is types.ListType:
                func[0](*func[1:])
            else:
                func()
        print ("[ " + utils.getColoredText(MSG_INFO_DONE, basedefs.GREEN) + " ]").rjust(spaceLen)
    except:
        print ("[ " + utils.getColoredText(MSG_INFO_ERROR, basedefs.RED) + " ]").rjust(spaceLen+3)
        raise

def isUpdateRelatedToDb(yumo):
    """
    Verifies current update needs DB manipulation (backup/update/rollback)
    """

    logging.debug("Verifing update is related to db")

    related = False
    for rpm in RPM_BACKEND, RPM_DBSCRIPTS:
        if yumo.isCandidateForUpdate(rpm):
            related = True

    logging.debug("isUpdateRelatedToDb value is %s"%(str(related)))
    return related

def printMessages():
    for msg in messages:
        logging.info(msg)
        print "* %s" % msg.strip()

def addAdditionalMessages(addReports=False):
    global messages
    messages.append(MSG_INFO_LOG_FILE + " " + LOG_FILE)

    if addReports:
        messages.append(MSG_INFO_REPORTS)


def stopDbRelatedServices(etlService, notificationService):
    """
    shut down etl and notifier services
    in order to disconnect any open sessions to the db
    """
    # If the ovirt-engine-etl service is installed, then try and stop it.
    if etlService.isServiceAvailable():
        try:
            etlService.stop(True)
        except:
            logging.warn("Failed to stop %s", etlService.name)
            logging.warn(traceback.format_exc())
            messages.append(MSG_ERR_FAILED_STOP_SERVICE % etlService.name)

    # If the ovirt-engine-notifierd service is up, then try and stop it.
    if notificationService.isServiceAvailable():
        try:
            (status, rc) = notificationService.status()
            if utils.verifyStringFormat(status, ".*running.*"):
                logging.debug("stopping %s service.", notificationService.name)
                notificationService.stop()
        except:
            logging.warn("Failed to stop %s service", notificationService.name)
            logging.warn(traceback.format_exc())
            messages.append(MSG_ERR_FAILED_STOP_SERVICE % notificationService.name)

def startDbRelatedServices(etlService, notificationService):
    """
    bring back any service we stopped
    we won't start services that are down
    but weren't stopped by us
    """
    if etlService.isServiceAvailable():
        (output, rc) = etlService.conditionalStart()
        if rc != 0:
            logging.warn("Failed to start %s", etlService.name)
            messages.append(MSG_ERR_FAILED_START_SERVICE % etlService.name)

    if notificationService.isServiceAvailable():
        (output, rc) = notificationService.conditionalStart()
        if rc != 0:
            logging.warn("Failed to start %s: exit code %d", notificationService.name, rc)
            messages.append(MSG_ERR_FAILED_START_SERVICE % notificationService.name)

def unsupportedVersionsPresent(oldversion=UNSUPPORTED_VERSION):
    """ Check whether there are UNSUPPORTED_VERSION
    objects present. If yes, throw an Exception
    """
    queryCheckDCVersions="SELECT compatibility_version FROM storage_pool;"
    dcVersions, rc = utils.execRemoteSqlCommand(
        SERVER_ADMIN,
        SERVER_NAME,
        SERVER_PORT,
        basedefs.DB_NAME,
        queryCheckDCVersions,
        True,
        MSG_ERROR_CONNECT_DB,
    )
    queryCheckClusterVersions="SELECT compatibility_version FROM vds_groups;"
    clusterVersions, rc = utils.execRemoteSqlCommand(
        SERVER_ADMIN,
        SERVER_NAME,
        SERVER_PORT,
        basedefs.DB_NAME,
        queryCheckClusterVersions,
        True,
        MSG_ERROR_CONNECT_DB
    )

    for versions in dcVersions, clusterVersions:
        if oldversion in versions:
            return True

    return False

def main(options):
    rhyum = MYum()
    db = DB()
    DB_NAME_TEMP = "%s_%s" % (basedefs.DB_NAME, utils.getCurrentDateTime())

    # Handle pgpass
    if not os.path.exists(basedefs.DB_PASS_FILE):
        if not os.path.exists(basedefs.ORIG_PASS_FILE):
            logging.error(MSG_ERROR_PGPASS)
            print MSG_ERROR_PGPASS
            sys.exit(1)
        else:
            logging.info("Info: Found .pgpass file at old location. Moving it to a new location.")
            shutil.copy(basedefs.ORIG_PASS_FILE, basedefs.DB_PASS_FILE)

            # File is copied/created by root, so no need to verify the owner.
            os.chmod(basedefs.DB_PASS_FILE, 0600)
    else:
        logging.info("Info: %s file found. Continue.", basedefs.DB_PASS_FILE)

    # Functions/parameters definitions
    stopEngineService = [stopEngine]
    upgradeFunc = [rhyum.update]
    postFunc = [runPost]
    engineService = basedefs.ENGINE_SERVICE_NAME
    # define db connections services
    etlService = utils.Service(basedefs.ETL_SERVICE_NAME)
    notificationService = utils.Service(basedefs.NOTIFIER_SERVICE_NAME)

    if unsupportedVersionsPresent():
        print MSG_ERROR_INCOMPATIBLE_UPGRADE
        raise Exception(MSG_ERROR_INCOMPATIBLE_UPGRADE)

    # Check for upgrade, else exit
    print MSG_INFO_CHECK_UPDATE
    if not rhyum.updateAvailable():
        logging.debug(MSG_INFO_NO_UPGRADE_AVAIL)
        print MSG_INFO_NO_UPGRADE_AVAIL
        sys.exit(0)
    else:
        updates = rhyum.getUpdateCandidates()
        print MSG_INFO_UPGRADE_AVAIL % (len(updates))
        for package in updates:
            print " * %s" % package
        if options.check_update:
            sys.exit(100)

    # Check for setup package
    if rhyum.isCandidateForUpdate(RPM_SETUP) and not options.force_current_setup_rpm:
        logging.debug(MSG_ERROR_NEW_SETUP_AVAIL)
        print MSG_ERROR_NEW_SETUP_AVAIL
        sys.exit(3)

    # Make sure we will be able to rollback
    if not rhyum.rollbackAvailable() and options.yum_rollback:
        logging.debug(MSG_ERROR_NO_ROLLBACK_AVAIL)
        print MSG_ERROR_NO_ROLLBACK_AVAIL
        print MSG_ERROR_CHECK_LOG%(LOG_FILE)
        sys.exit(2)

    # No rollback in this case
    try:
        # We ask the user before stoping ovirt-engine or take command line option
        if options.unattended_upgrade or checkEngine(engineService):
            # Stopping engine
            runFunc(stopEngineService, MSG_INFO_STOP_ENGINE)
        else:
            # This means that user chose not to stop ovirt-engine
            logging.debug("exiting gracefully")
            print MSG_INFO_STOP_INSTALL_EXIT
            sys.exit(0)

        # Backup DB
        if isUpdateRelatedToDb(rhyum):
            stopDbRelatedServices(etlService, notificationService)
            runFunc([db.backup], MSG_INFO_BACKUP_DB)
            runFunc([[db.rename, DB_NAME_TEMP]], MSG_INFO_RENAME_DB)

    except Exception as e:
        print e
        raise

    # In case of failure, do rollback
    try:
        # yum update
        runFunc(upgradeFunc, MSG_INFO_YUM_UPDATE)

        # If we're here, update/upgrade went fine, so
        rhyum.updated = True

        # check if update is relevant to db update
        if isUpdateRelatedToDb(rhyum):

            # Update the db and restore its name back
            runFunc([db.update], MSG_INFO_DB_UPDATE)
            runFunc([[db.rename, basedefs.DB_NAME]], MSG_INFO_RESTORE_DB)

            # Bring up any services we shut down before db upgrade
            startDbRelatedServices(etlService, notificationService)

        # post install conf
        runFunc(postFunc, MSG_INFO_RUN_POST)

    except:
        logging.error(traceback.format_exc())
        logging.error("Rolling back update")

        print MSG_ERROR_UPGRADE
        print MSG_INFO_REASON%(sys.exc_info()[1])

        # allow db restore
        if isUpdateRelatedToDb(rhyum):
            try:
                runFunc([db.restore], MSG_INFO_DB_RESTORE)
            except:
                # This Exception have already been logged, so just pass along
                pass

        # allow yum rollback even if db restore failed
        if options.yum_rollback:
            try:
                runFunc([rhyum.rollback], MSG_INFO_YUM_ROLLBACK)
            except:
                # This Exception have already been logged, so just pass along
                pass
        else:
            print MSG_INFO_NO_YUM_ROLLBACK
            logging.debug("Skipping yum rollback")

        raise

    finally:
        # start engine
        runFunc([startEngine], MSG_INFO_START_ENGINE)

    # Print log location on success
    addAdditionalMessages(etlService.isServiceAvailable())
    print "\n%s\n" % MSG_INFO_UPGRADE_OK
    printMessages()

if __name__ == '__main__':
    try:
        # Must run as root
        _verifyUserPermissions()

        # Init logging facility
        initLogging()

        # DB Configuration
        SERVER_NAME = utils.getDbHostName()
        SERVER_PORT = utils.getDbPort()
        SERVER_ADMIN = utils.getDbUser()

        # get iso and domain from user arguments
        (options, args) = getOptions()

        main(options)

    except SystemExit:
        raise

    except:
        print MSG_ERROR_CHECK_LOG%(LOG_FILE)
        logging.error(traceback.format_exc())
        sys.exit(1)
