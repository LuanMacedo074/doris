// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource;

import org.apache.doris.analysis.CreateCatalogStmt;
import org.apache.doris.analysis.DropCatalogStmt;
import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.DatabaseIf;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.EnvFactory;
import org.apache.doris.catalog.Resource;
import org.apache.doris.catalog.Resource.ReferenceType;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.CaseSensibility;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.PatternMatcher;
import org.apache.doris.common.PatternMatcherWrapper;
import org.apache.doris.common.UserException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.io.Writable;
import org.apache.doris.common.lock.MonitoredReentrantReadWriteLock;
import org.apache.doris.common.util.PrintableMap;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.common.util.Util;
import org.apache.doris.datasource.hive.HMSExternalCatalog;
import org.apache.doris.datasource.hive.HMSExternalDatabase;
import org.apache.doris.datasource.hive.HMSExternalTable;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.nereids.trees.plans.commands.CreateCatalogCommand;
import org.apache.doris.persist.OperationType;
import org.apache.doris.persist.gson.GsonPostProcessable;
import org.apache.doris.persist.gson.GsonUtils;
import org.apache.doris.qe.ConnectContext;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CatalogMgr will load all catalogs at FE startup,
 * and save them in map with name.
 * Note: Catalog in sql syntax will be treated as catalog interface in code level.
 * TODO: Change the package name into catalog.
 */
public class CatalogMgr implements Writable, GsonPostProcessable {
    private static final Logger LOG = LogManager.getLogger(CatalogMgr.class);

    public static final String ACCESS_CONTROLLER_CLASS_PROP = "access_controller.class";
    public static final String ACCESS_CONTROLLER_PROPERTY_PREFIX_PROP = "access_controller.properties.";
    public static final String METADATA_REFRESH_INTERVAL_SEC = "metadata_refresh_interval_sec";
    public static final String CATALOG_TYPE_PROP = "type";

    private final MonitoredReentrantReadWriteLock lock = new MonitoredReentrantReadWriteLock(true);

    @SerializedName(value = "idToCatalog")
    private Map<Long, CatalogIf<? extends DatabaseIf<? extends TableIf>>> idToCatalog = Maps.newConcurrentMap();
    // this map will be regenerated from idToCatalog, so not need to persist.
    private Map<String, CatalogIf> nameToCatalog = Maps.newConcurrentMap();

    // Use a separate instance to facilitate access.
    // internalDataSource still exists in idToCatalog and nameToCatalog
    private InternalCatalog internalCatalog;

    public CatalogMgr() {
        initInternalCatalog();
    }

    public static CatalogMgr read(DataInput in) throws IOException {
        String json = Text.readString(in);
        if (LOG.isDebugEnabled()) {
            LOG.debug("debug: read json: {}", json);
        }
        return GsonUtils.GSON.fromJson(json, CatalogMgr.class);
    }

    private void initInternalCatalog() {
        internalCatalog = EnvFactory.getInstance().createInternalCatalog();
        addCatalog(internalCatalog);
    }

    private void addCatalog(CatalogIf catalog) {
        nameToCatalog.put(catalog.getName(), catalog);
        idToCatalog.put(catalog.getId(), catalog);
        String catalogName = catalog.getName();
        if (!catalogName.equals(InternalCatalog.INTERNAL_CATALOG_NAME)) {
            ((ExternalCatalog) catalog).resetToUninitialized(false);
        }
        if (!Strings.isNullOrEmpty(catalog.getResource())) {
            Resource resource = Env.getCurrentEnv().getResourceMgr().getResource(catalog.getResource());
            if (resource != null) {
                resource.addReference(catalog.getName(), ReferenceType.CATALOG);
            }
        }
    }

    private CatalogIf removeCatalog(long catalogId) {
        CatalogIf catalog = idToCatalog.remove(catalogId);
        LOG.info("Removed catalog with id {}, name {}", catalogId, catalog == null ? "N/A" : catalog.getName());
        if (catalog != null) {
            catalog.onClose();
            nameToCatalog.remove(catalog.getName());
            if (ConnectContext.get() != null) {
                ConnectContext.get().removeLastDBOfCatalog(catalog.getName());
            }
            Env.getCurrentEnv().getExtMetaCacheMgr().removeCache(catalog.getId());
            if (!Strings.isNullOrEmpty(catalog.getResource())) {
                Resource catalogResource = Env.getCurrentEnv().getResourceMgr().getResource(catalog.getResource());
                if (catalogResource != null) {
                    catalogResource.removeReference(catalog.getName(), ReferenceType.CATALOG);
                }
            }
            Env.getCurrentEnv().getQueryStats().clear(catalog.getId());
        }
        return catalog;
    }

    public InternalCatalog getInternalCatalog() {
        return internalCatalog;
    }

    public CatalogIf getCatalog(String name) {
        return nameToCatalog.get(name);
    }

    public CatalogIf<? extends DatabaseIf<? extends TableIf>> getCatalog(long id) {
        return idToCatalog.get(id);
    }

    public CatalogIf getCatalogOrAnalysisException(long id) throws AnalysisException {
        return getCatalogOrException(id,
                catalog -> new AnalysisException(ErrorCode.ERR_UNKNOWN_CATALOG.formatErrorMsg(catalog),
                        ErrorCode.ERR_UNKNOWN_CATALOG));
    }

    public <E extends Exception> CatalogIf<? extends DatabaseIf<? extends TableIf>>
            getCatalogOrException(long id, Function<Long, E> e) throws E {
        CatalogIf catalog = idToCatalog.get(id);
        if (catalog == null) {
            throw e.apply(id);
        }
        return catalog;
    }

    public <E extends Exception> CatalogIf getCatalogOrException(String name, Function<String, E> e) throws E {
        CatalogIf catalog = nameToCatalog.get(name);
        if (catalog == null) {
            throw e.apply(name);
        }
        return catalog;
    }

    public CatalogIf getCatalogOrDdlException(String name) throws DdlException {
        return getCatalogOrException(name,
                catalog -> new DdlException(ErrorCode.ERR_UNKNOWN_CATALOG.formatErrorMsg(catalog),
                        ErrorCode.ERR_UNKNOWN_CATALOG));
    }

    public CatalogIf getCatalogOrAnalysisException(String name) throws AnalysisException {
        return getCatalogOrException(name,
                catalog -> new AnalysisException(ErrorCode.ERR_UNKNOWN_CATALOG.formatErrorMsg(catalog),
                        ErrorCode.ERR_UNKNOWN_CATALOG));
    }

    public List<Long> getCatalogIds() {
        return Lists.newArrayList(idToCatalog.keySet());
    }

    /**
     * Get db allow return null
     **/
    public DatabaseIf getDbNullable(long dbId) {
        DatabaseIf db = internalCatalog.getDbNullable(dbId);
        if (db != null) {
            return db;
        }
        for (CatalogIf catalog : nameToCatalog.values()) {
            if (catalog == internalCatalog) {
                continue;
            }
            db = catalog.getDbNullable(dbId);
            if (db != null) {
                return db;
            }
        }
        return null;
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }

    private void readLock() {
        lock.readLock().lock();
    }

    private void readUnlock() {
        lock.readLock().unlock();
    }

    private void createCatalogImpl(CatalogIf catalog, String catalogName,
            boolean ifNotExists) throws UserException {
        writeLock();
        try {
            if (nameToCatalog.containsKey(catalog.getName())) {
                if (ifNotExists) {
                    LOG.warn("Catalog {} is already exist.", catalogName);
                    return;
                }
                throw new DdlException("Catalog had already exist with name: " + catalogName);
            }
            createCatalogInternal(catalog, false);
            Env.getCurrentEnv().getEditLog().logCatalogLog(OperationType.OP_CREATE_CATALOG, catalog.constructEditLog());
        } finally {
            writeUnlock();
        }
    }

    /**
     * Create and hold the catalog instance and write the meta log.
     */
    public void createCatalog(CreateCatalogCommand cmd) throws UserException {
        long id = Env.getCurrentEnv().getNextId();
        CatalogIf catalog = CatalogFactory.createFromCommand(id, cmd);
        createCatalogImpl(catalog, cmd.getCatalogName(), cmd.isSetIfNotExists());
    }

    /**
     * Create and hold the catalog instance and write the meta log.
     */
    public void createCatalog(CreateCatalogStmt stmt) throws UserException {
        long id = Env.getCurrentEnv().getNextId();
        CatalogIf catalog = CatalogFactory.createFromStmt(id, stmt);
        createCatalogImpl(catalog, stmt.getCatalogName(), stmt.isSetIfNotExists());
    }

    /**
     * Remove the catalog instance by name and write the meta log.
     */
    public void dropCatalog(String catalogName, boolean ifExists) throws UserException {
        writeLock();
        try {
            if (ifExists && !nameToCatalog.containsKey(catalogName)) {
                LOG.warn("Non catalog {} is found.", catalogName);
                return;
            }
            CatalogIf<DatabaseIf<TableIf>> catalog = nameToCatalog.get(catalogName);
            if (catalog == null) {
                throw new DdlException("No catalog found with name: " + catalogName);
            }
            CatalogLog log = new CatalogLog();
            log.setCatalogId(catalog.getId());
            replayDropCatalog(log);
            Env.getCurrentEnv().getEditLog().logCatalogLog(OperationType.OP_DROP_CATALOG, log);

            if (ConnectContext.get() != null) {
                ConnectContext.get().removeLastDBOfCatalog(catalogName);
            }
            Env.getCurrentEnv().getQueryStats().clear(catalog.getId());
            LOG.info("finished to drop catalog {}:{}", catalog.getName(), catalog.getId());
        } finally {
            writeUnlock();
        }
    }

    /**
     * Remove the catalog instance by name and write the meta log.
     */
    public void dropCatalog(DropCatalogStmt stmt) throws UserException {
        dropCatalog(stmt.getCatalogName(), stmt.isSetIfExists());
    }

    /**
     * Modify the catalog name into a new one and write the meta log.
     */
    public void alterCatalogName(String catalogName, String newCatalogName) throws UserException {
        writeLock();
        try {
            CatalogIf catalog = nameToCatalog.get(catalogName);
            if (catalog == null) {
                throw new DdlException("No catalog found with name: " + catalogName);
            }
            if (nameToCatalog.get(newCatalogName) != null) {
                throw new DdlException("Catalog with name " + newCatalogName + " already exist");
            }
            CatalogLog log = new CatalogLog();
            log.setCatalogId(catalog.getId());
            log.setNewCatalogName(newCatalogName);
            replayAlterCatalogName(log);
            Env.getCurrentEnv().getEditLog().logCatalogLog(OperationType.OP_ALTER_CATALOG_NAME, log);

            ConnectContext ctx = ConnectContext.get();
            if (ctx != null) {
                String db = ctx.getLastDBOfCatalog(catalogName);
                if (db != null) {
                    ctx.removeLastDBOfCatalog(catalogName);
                    ctx.addLastDBOfCatalog(log.getNewCatalogName(), db);
                }
            }
        } finally {
            writeUnlock();
        }
    }

    /**
     * Modify the catalog comment to a new one and write the meta log.
     */
    public void alterCatalogComment(String catalogName, String comment) throws UserException {
        writeLock();
        try {
            CatalogIf catalog = nameToCatalog.get(catalogName);
            if (catalog == null) {
                throw new DdlException("No catalog found with name: " + catalogName);
            }
            CatalogLog log = new CatalogLog();
            log.setCatalogId(catalog.getId());
            log.setComment(comment);
            replayAlterCatalogComment(log);
            Env.getCurrentEnv().getEditLog().logCatalogLog(OperationType.OP_ALTER_CATALOG_COMMENT, log);
        } finally {
            writeUnlock();
        }
    }

    /**
     * Modify the catalog property and write the meta log.
     */
    public void alterCatalogProps(String catalogName, Map<String, String> newProperties) throws UserException {
        writeLock();
        try {
            CatalogIf catalog = nameToCatalog.get(catalogName);
            if (catalog == null) {
                throw new DdlException("No catalog found with name: " + catalogName);
            }
            Map<String, String> oldProperties = catalog.getProperties();
            if (newProperties.containsKey("type") && !catalog.getType()
                    .equalsIgnoreCase(newProperties.get("type"))) {
                throw new DdlException("Can't modify the type of catalog property with name: " + catalogName);
            }
            CatalogLog log = new CatalogLog();
            log.setCatalogId(catalog.getId());
            log.setNewProps(newProperties);
            replayAlterCatalogProps(log, oldProperties, false);
            Env.getCurrentEnv().getEditLog().logCatalogLog(OperationType.OP_ALTER_CATALOG_PROPS, log);
        } finally {
            writeUnlock();
        }
    }

    public List<List<String>> showCatalogs(
            String catalogName, String pattern, String currentCatalogName) throws AnalysisException {
        List<List<String>> rows = Lists.newArrayList();
        readLock();
        try {
            if (catalogName == null) {
                PatternMatcher matcher = null;
                if (pattern != null) {
                    matcher = PatternMatcherWrapper.createMysqlPattern(pattern,
                        CaseSensibility.CATALOG.getCaseSensibility());
                }
                for (CatalogIf catalog : listCatalogsWithCheckPriv(ConnectContext.get().getCurrentUserIdentity())) {
                    String name = catalog.getName();
                    // Filter catalog name
                    if (matcher != null && !matcher.match(name)) {
                        continue;
                    }
                    List<String> row = Lists.newArrayList();
                    row.add(String.valueOf(catalog.getId()));
                    row.add(name);
                    row.add(catalog.getType());
                    if (name.equals(currentCatalogName)) {
                        row.add("Yes");
                    } else {
                        row.add("No");
                    }
                    Map<String, String> props = catalog.getProperties();
                    String createTime = props.getOrDefault(ExternalCatalog.CREATE_TIME, FeConstants.null_string);
                    row.add(createTime);
                    row.add(TimeUtils.longToTimeString(catalog.getLastUpdateTime()));
                    row.add(catalog.getComment());
                    rows.add(row);

                    // sort by catalog name
                    rows.sort((x, y) -> {
                        return x.get(1).compareTo(y.get(1));
                    });
                }
            } else {
                if (!nameToCatalog.containsKey(catalogName)) {
                    throw new AnalysisException("No catalog found with name: " + catalogName);
                }
                CatalogIf<DatabaseIf> catalog = nameToCatalog.get(catalogName);
                if (!Env.getCurrentEnv().getAccessManager()
                        .checkCtlPriv(ConnectContext.get(), catalog.getName(), PrivPredicate.SHOW)) {
                    ErrorReport.reportAnalysisException(
                            ErrorCode.ERR_CATALOG_ACCESS_DENIED,
                            ConnectContext.get().getQualifiedUser(),
                            catalog.getName());
                }
                if (!Strings.isNullOrEmpty(catalog.getResource())) {
                    rows.add(Arrays.asList("resource", catalog.getResource()));
                }
                Map<String, String> sortedMap = getCatalogPropertiesWithPrintable(catalog);
                sortedMap.forEach((k, v) -> rows.add(Arrays.asList(k, v)));
            }
        } finally {
            readUnlock();
        }

        return rows;
    }

    public static Map<String, String> getCatalogPropertiesWithPrintable(CatalogIf<?> catalog) {
        // use tree map to maintain display order, making it easier to view properties
        Map<String, String> sortedMap = new TreeMap<>();
        catalog.getProperties().forEach(
                (key, value) -> {
                    if (PrintableMap.HIDDEN_KEY.contains(key)) {
                        return;
                    }
                    if (PrintableMap.SENSITIVE_KEY.contains(key)) {
                        sortedMap.put(key, PrintableMap.PASSWORD_MASK);
                    } else {
                        sortedMap.put(key, value);
                    }
                }
        );
        return sortedMap;
    }

    public List<List<String>> showCreateCatalog(String catalogName) throws AnalysisException {
        List<List<String>> rows = Lists.newArrayList();
        readLock();
        try {
            CatalogIf catalog = nameToCatalog.get(catalogName);
            if (catalog == null) {
                throw new AnalysisException("No catalog found with name " + catalogName);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\nCREATE CATALOG `").append(ClusterNamespace.getNameFromFullName(catalogName))
                    .append("`");
            if (!Strings.isNullOrEmpty(catalog.getComment())) {
                sb.append("\nCOMMENT \"").append(catalog.getComment()).append("\"\n");
            }
            if (catalog.getProperties().size() > 0) {
                sb.append(" PROPERTIES (\n");
                PrintableMap<String, String> printableMap = new PrintableMap<>(catalog.getProperties(), "=", true, true,
                        true, true);
                printableMap.setAdditionalHiddenKeys(ExternalCatalog.HIDDEN_PROPERTIES);
                sb.append(printableMap);
                sb.append("\n);");
            }

            rows.add(Lists.newArrayList(ClusterNamespace.getNameFromFullName(catalogName), sb.toString()));
        } finally {
            readUnlock();
        }

        return rows;
    }

    /**
     * Reply for create catalog event.
     */
    public void replayCreateCatalog(CatalogLog log) throws DdlException {
        CatalogIf catalog = CatalogFactory.createFromLog(log);
        createCatalogInternal(catalog, true);
    }

    private void createCatalogInternal(CatalogIf catalog, boolean isReplay) throws DdlException {
        writeLock();
        try {
            if (!isReplay && catalog instanceof ExternalCatalog) {
                ((ExternalCatalog) catalog).checkProperties();
            }
            Map<String, String> props = catalog.getProperties();
            if (props.containsKey(METADATA_REFRESH_INTERVAL_SEC)) {
                // need refresh
                long catalogId = catalog.getId();
                Integer metadataRefreshIntervalSec = Integer.valueOf(props.get(METADATA_REFRESH_INTERVAL_SEC));
                Integer[] sec = {metadataRefreshIntervalSec, metadataRefreshIntervalSec};
                Env.getCurrentEnv().getRefreshManager().addToRefreshMap(catalogId, sec);
            }
            addCatalog(catalog);
            LOG.info("finished to create catalog {}:{}, is replay: {}", catalog.getName(), catalog.getId(), isReplay);
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for drop catalog event.
     */
    public void replayDropCatalog(CatalogLog log) {
        writeLock();
        try {
            removeCatalog(log.getCatalogId());
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for alter catalog name event.
     */
    public void replayAlterCatalogName(CatalogLog log) {
        writeLock();
        try {
            CatalogIf catalog = removeCatalog(log.getCatalogId());
            catalog.modifyCatalogName(log.getNewCatalogName());
            addCatalog(catalog);
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for alter catalog comment event.
     */
    public void replayAlterCatalogComment(CatalogLog log) {
        writeLock();
        try {
            CatalogIf catalog = idToCatalog.get(log.getCatalogId());
            if (catalog != null) {
                catalog.setComment(log.getComment());
            }
        } finally {
            writeUnlock();
        }
    }

    public List<CatalogIf> listCatalogs() {
        return nameToCatalog.values().stream().collect(Collectors.toList());
    }

    public List<CatalogIf> listCatalogsWithCheckPriv(UserIdentity userIdentity) {
        return nameToCatalog.values().stream().filter(
                catalog -> Env.getCurrentEnv().getAccessManager()
                        .checkCtlPriv(userIdentity, catalog.getName(), PrivPredicate.SHOW)
        ).collect(Collectors.toList());
    }


    /**
     * Reply for alter catalog props event.
     */
    public void replayAlterCatalogProps(CatalogLog log, Map<String, String> oldProperties, boolean isReplay)
            throws DdlException {
        writeLock();
        try {
            CatalogIf catalog = idToCatalog.get(log.getCatalogId());
            if (catalog instanceof ExternalCatalog) {
                Map<String, String> newProps = log.getNewProps();
                ((ExternalCatalog) catalog).tryModifyCatalogProps(newProps);
                if (!isReplay) {
                    try {
                        ((ExternalCatalog) catalog).checkProperties();
                    } catch (DdlException ddlException) {
                        if (oldProperties != null) {
                            ((ExternalCatalog) catalog).rollBackCatalogProps(oldProperties);
                        }
                        throw ddlException;
                    }
                }
                if (newProps.containsKey(METADATA_REFRESH_INTERVAL_SEC)) {
                    long catalogId = catalog.getId();
                    Integer metadataRefreshIntervalSec = Integer.valueOf(newProps.get(METADATA_REFRESH_INTERVAL_SEC));
                    Integer[] sec = {metadataRefreshIntervalSec, metadataRefreshIntervalSec};
                    Env.getCurrentEnv().getRefreshManager().addToRefreshMap(catalogId, sec);
                }
            }
            catalog.modifyCatalogProps(log.getNewProps());
        } finally {
            writeUnlock();
        }
    }

    // init catalog and init db can happen at any time,
    // even after catalog or db is dropped.
    // Because it may already hold the catalog or db object before they are being dropped.
    // So just skip the edit log if object does not exist.
    public void replayInitCatalog(InitCatalogLog log) {
        ExternalCatalog catalog = (ExternalCatalog) idToCatalog.get(log.getCatalogId());
        if (catalog == null) {
            return;
        }
        catalog.replayInitCatalog(log);
    }

    public void replayInitExternalDb(InitDatabaseLog log) {
        ExternalCatalog catalog = (ExternalCatalog) idToCatalog.get(log.getCatalogId());
        if (catalog == null) {
            return;
        }
        Optional<ExternalDatabase<? extends ExternalTable>> db = catalog.getDbForReplay(log.getDbId());
        if (!db.isPresent()) {
            return;
        }
        db.get().replayInitDb(log, catalog);
    }

    public void unregisterExternalTable(String dbName, String tableName, String catalogName, boolean ignoreIfExists)
            throws DdlException {
        CatalogIf<?> catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support drop ExternalCatalog Tables");
        }
        ExternalDatabase<?> db = ((ExternalCatalog) catalog).getDbNullable(dbName);
        if (db == null) {
            if (!ignoreIfExists) {
                throw new DdlException("Database " + dbName + " does not exist in catalog " + catalog.getName());
            }
            return;
        }

        TableIf table = db.getTableNullable(tableName);
        if (table == null) {
            if (!ignoreIfExists) {
                throw new DdlException("Table " + tableName + " does not exist in db " + dbName);
            }
            return;
        }

        db.writeLock();
        try {
            db.unregisterTable(table.getName());
        } finally {
            db.writeUnlock();
        }
    }

    public boolean externalTableExistInLocal(String dbName, String tableName, String catalogName) throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support ExternalCatalog Tables");
        }
        return ((ExternalCatalog) catalog).tableExistInLocal(dbName, tableName);
    }

    public void registerExternalTableFromEvent(String dbName, String tableName,
            String catalogName, long updateTime,
            boolean ignoreIfExists) throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support create ExternalCatalog Tables");
        }
        DatabaseIf db = catalog.getDbNullable(dbName);
        if (db == null) {
            if (!ignoreIfExists) {
                throw new DdlException("Database " + dbName + " does not exist in catalog " + catalog.getName());
            }
            return;
        }

        long tblId;
        HMSExternalCatalog hmsCatalog = (HMSExternalCatalog) catalog;
        if (hmsCatalog.getUseMetaCache().get()) {
            tblId = Util.genIdByName(catalogName, dbName, tableName);
        } else {
            tblId = Env.getCurrentEnv().getExternalMetaIdMgr().getTblId(catalog.getId(), dbName, tableName);
        }
        // -1L means it will be dropped later, ignore
        if (tblId == ExternalMetaIdMgr.META_ID_FOR_NOT_EXISTS) {
            return;
        }

        db.writeLock();
        try {
            HMSExternalTable namedTable = ((HMSExternalDatabase) db)
                    .buildTableForInit(tableName, tableName, tblId, hmsCatalog, (HMSExternalDatabase) db, false);
            namedTable.setUpdateTime(updateTime);
            db.registerTable(namedTable);
        } finally {
            db.writeUnlock();
        }
    }

    public void unregisterExternalDatabase(String dbName, String catalogName)
            throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support drop ExternalCatalog databases");
        }
        ((HMSExternalCatalog) catalog).unregisterDatabase(dbName);
    }

    public void registerExternalDatabaseFromEvent(String dbName, String catalogName)
            throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support create ExternalCatalog databases");
        }

        HMSExternalCatalog hmsCatalog = (HMSExternalCatalog) catalog;
        long dbId;
        if (hmsCatalog.getUseMetaCache().get()) {
            dbId = Util.genIdByName(catalogName, dbName);
        } else {
            dbId = Env.getCurrentEnv().getExternalMetaIdMgr().getDbId(catalog.getId(), dbName);
        }
        // -1L means it will be dropped later, ignore
        if (dbId == ExternalMetaIdMgr.META_ID_FOR_NOT_EXISTS) {
            return;
        }

        hmsCatalog.registerDatabase(dbId, dbName);
    }

    public void addExternalPartitions(String catalogName, String dbName, String tableName,
            List<String> partitionNames, long updateTime, boolean ignoreIfNotExists)
            throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support ExternalCatalog");
        }
        DatabaseIf db = catalog.getDbNullable(dbName);
        if (db == null) {
            if (!ignoreIfNotExists) {
                throw new DdlException("Database " + dbName + " does not exist in catalog " + catalog.getName());
            }
            return;
        }

        TableIf table = db.getTableNullable(tableName);
        if (table == null) {
            if (!ignoreIfNotExists) {
                throw new DdlException("Table " + tableName + " does not exist in db " + dbName);
            }
            return;
        }
        if (!(table instanceof HMSExternalTable)) {
            LOG.warn("only support HMSTable");
            return;
        }

        HMSExternalTable hmsTable = (HMSExternalTable) table;
        Env.getCurrentEnv().getExtMetaCacheMgr().addPartitionsCache(catalog.getId(), hmsTable, partitionNames);
        hmsTable.setEventUpdateTime(updateTime);
    }

    public void dropExternalPartitions(String catalogName, String dbName, String tableName,
            List<String> partitionNames, long updateTime, boolean ignoreIfNotExists)
            throws DdlException {
        CatalogIf catalog = nameToCatalog.get(catalogName);
        if (catalog == null) {
            throw new DdlException("No catalog found with name: " + catalogName);
        }
        if (!(catalog instanceof ExternalCatalog)) {
            throw new DdlException("Only support ExternalCatalog");
        }
        DatabaseIf db = catalog.getDbNullable(dbName);
        if (db == null) {
            if (!ignoreIfNotExists) {
                throw new DdlException("Database " + dbName + " does not exist in catalog " + catalog.getName());
            }
            return;
        }

        TableIf table = db.getTableNullable(tableName);
        if (table == null) {
            if (!ignoreIfNotExists) {
                throw new DdlException("Table " + tableName + " does not exist in db " + dbName);
            }
            return;
        }

        HMSExternalTable hmsTable = (HMSExternalTable) table;
        Env.getCurrentEnv().getExtMetaCacheMgr().dropPartitionsCache(catalog.getId(), hmsTable, partitionNames);
        hmsTable.setEventUpdateTime(updateTime);
    }

    public void registerCatalogRefreshListener(Env env) {
        for (CatalogIf catalog : idToCatalog.values()) {
            Map<String, String> properties = catalog.getProperties();
            if (properties.containsKey(METADATA_REFRESH_INTERVAL_SEC)) {
                Integer metadataRefreshIntervalSec = Integer.valueOf(properties.get(METADATA_REFRESH_INTERVAL_SEC));
                Integer[] sec = {metadataRefreshIntervalSec, metadataRefreshIntervalSec};
                env.getRefreshManager().addToRefreshMap(catalog.getId(), sec);
            }
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        String json = GsonUtils.GSON.toJson(this);
        Text.writeString(out, json);
    }

    @Override
    public void gsonPostProcess() throws IOException {
        // After deserializing from Gson, the concurrent map may become a normal map.
        // So here we reconstruct the concurrent map.
        Map<Long, CatalogIf<? extends DatabaseIf<? extends TableIf>>> newIdToCatalog = Maps.newConcurrentMap();
        Map<String, CatalogIf> newNameToCatalog = Maps.newConcurrentMap();
        for (CatalogIf catalog : idToCatalog.values()) {
            newNameToCatalog.put(catalog.getName(), catalog);
            newIdToCatalog.put(catalog.getId(), catalog);
            // ATTN: can not call catalog.getProperties() here, because ResourceMgr is not replayed yet.
        }
        this.idToCatalog = newIdToCatalog;
        this.nameToCatalog = newNameToCatalog;
        internalCatalog = (InternalCatalog) idToCatalog.get(InternalCatalog.INTERNAL_CATALOG_ID);
    }

    public Map<Long, CatalogIf<? extends DatabaseIf<? extends TableIf>>> getIdToCatalog() {
        return idToCatalog;
    }

    public Set<CatalogIf> getCopyOfCatalog() {
        return new HashSet<>(idToCatalog.values());
    }

    public int getCatalogNum() {
        return idToCatalog.size();
    }
}
