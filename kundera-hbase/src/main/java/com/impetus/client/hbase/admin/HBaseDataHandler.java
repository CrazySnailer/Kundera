/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.hbase.admin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.ElementCollection;
import javax.persistence.Embedded;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EmbeddableType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;

import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.util.Bytes;
import org.jboss.netty.util.internal.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.client.hbase.HBaseData;
import com.impetus.client.hbase.Reader;
import com.impetus.client.hbase.Writer;
import com.impetus.client.hbase.service.HBaseReader;
import com.impetus.client.hbase.service.HBaseWriter;
import com.impetus.client.hbase.utils.HBaseUtils;
import com.impetus.kundera.Constants;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.cache.ElementCollectionCacheManager;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.MetadataUtils;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorHelper;

/**
 * The Class HBaseDataHandler.
 * 
 * @author vivek.mishra
 */
public class HBaseDataHandler implements DataHandler
{
    /** the log used by this class. */
    private static Logger log = LoggerFactory.getLogger(HBaseDataHandler.class);

    /** The admin. */
    private HBaseAdmin admin;

    /** The conf. */
    private HBaseConfiguration conf;

    /** The h table pool. */
    private HTablePool hTablePool;

    /** The hbase reader. */
    private Reader hbaseReader = new HBaseReader();

    /** The hbase writer. */
    private Writer hbaseWriter = new HBaseWriter();

    private FilterList filter = null;

    private Map<String, FilterList> filters = new ConcurrentHashMap<String, FilterList>();

    /**
     * Instantiates a new h base data handler.
     * 
     * @param conf
     *            the conf
     * @param hTablePool
     *            the h table pool
     */
    public HBaseDataHandler(HBaseConfiguration conf, HTablePool hTablePool)
    {
        try
        {
            this.conf = conf;
            this.hTablePool = hTablePool;
            this.admin = new HBaseAdmin(conf);
        }
        catch (Exception e)
        {
            // TODO We need a generic ExceptionTranslator
            throw new PersistenceException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#createTableIfDoesNotExist(
     * java.lang.String, java.lang.String[])
     */
    @Override
    public void createTableIfDoesNotExist(final String tableName, final String... colFamily)
            throws MasterNotRunningException, IOException
    {
        if (!admin.tableExists(Bytes.toBytes(tableName)))
        {
            HTableDescriptor htDescriptor = new HTableDescriptor(tableName);
            for (String columnFamily : colFamily)
            {
                HColumnDescriptor familyMetadata = new HColumnDescriptor(columnFamily);
                htDescriptor.addFamily(familyMetadata);
            }
            admin.createTable(htDescriptor);
        }
    }

    /**
     * Adds the column family to table.
     * 
     * @param tableName
     *            the table name
     * @param columnFamilyName
     *            the column family name
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void addColumnFamilyToTable(String tableName, String columnFamilyName) throws IOException
    {
        HColumnDescriptor cfDesciptor = new HColumnDescriptor(columnFamilyName);

        try
        {
            if (admin.tableExists(tableName))
            {

                // Before any modification to table schema, it's necessary to
                // disable it
                if (!admin.isTableEnabled(tableName))
                {
                    admin.enableTable(tableName);
                }
                HTableDescriptor descriptor = admin.getTableDescriptor(tableName.getBytes());
                boolean found = false;
                for (HColumnDescriptor hColumnDescriptor : descriptor.getColumnFamilies())
                {
                    if (hColumnDescriptor.getNameAsString().equalsIgnoreCase(columnFamilyName))
                        found = true;
                }
                if (!found)
                {

                    if (admin.isTableEnabled(tableName))
                    {
                        admin.disableTable(tableName);
                    }

                    admin.addColumn(tableName, cfDesciptor);

                    // Enable table once done
                    admin.enableTable(tableName);
                }
            }
            else
            {
                log.warn("Table {} doesn't exist, so no question of adding column family {} to it!", tableName,
                        columnFamilyName);
            }
        }
        catch (IOException e)
        {
            log.error("Error while adding column family {}, to table{} . ", columnFamilyName, tableName);
            throw e;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#readData(java.lang.String,
     * java.lang.Class, com.impetus.kundera.metadata.model.EntityMetadata,
     * java.lang.String, java.util.List)
     */
    @Override
    public List readData(final String tableName, Class clazz, EntityMetadata m, final Object rowKey,
            List<String> relationNames, FilterList f, String... columns) throws IOException
    {

        List output = null;

        Object entity = null;

        HTableInterface hTable = null;

        hTable = gethTable(tableName);

        if (getFilter(m.getTableName()) != null)
        {
            if (f == null)
            {
                f = new FilterList();
            }
            f.addFilter(getFilter(m.getTableName()));
        }

        // Load raw data from HBase
        List<HBaseData> results = hbaseReader.LoadData(hTable, m.getTableName(), rowKey, f, columns);
        output = onRead(tableName, clazz, m, output, hTable, entity, relationNames, results);
        return output;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#readData(java.lang.String,
     * java.lang.Class, com.impetus.kundera.metadata.model.EntityMetadata,
     * java.lang.String, java.util.List)
     */
    @Override
    public List readAll(final String tableName, Class clazz, EntityMetadata m, final List<Object> rowKey,
            List<String> relationNames, String... columns) throws IOException
    {

        List output = null;

        Object entity = null;

        HTableInterface hTable = null;

        hTable = gethTable(tableName);

        // Load raw data from HBase
        List<HBaseData> results = ((HBaseReader) hbaseReader).loadAll(hTable, rowKey, m.getTableName(), columns);
        output = onRead(tableName, clazz, m, output, hTable, entity, relationNames, results);
        return output;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#readDataByRange(java.lang.
     * String, java.lang.Class,
     * com.impetus.kundera.metadata.model.EntityMetadata, java.util.List,
     * byte[], byte[])
     */
    @Override
    public List readDataByRange(String tableName, Class clazz, EntityMetadata m, byte[] startRow, byte[] endRow,
            String[] columns, FilterList f) throws IOException
    {
        List output = new ArrayList();
        HTableInterface hTable = null;
        Object entity = null;
        List<String> relationNames = m.getRelationNames();

        if (getFilter(m.getTableName()) != null)
        {
            if (f == null)
            {
                f = new FilterList();
            }
            f.addFilter(getFilter(m.getTableName()));
        }
        // Load raw data from HBase
        hTable = gethTable(tableName);
        List<HBaseData> results = hbaseReader.loadAll(hTable, f, startRow, endRow, m.getTableName(), null, columns);
        output = onRead(tableName, clazz, m, output, hTable, entity, relationNames, results);

        return output;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#writeData(java.lang.String,
     * com.impetus.kundera.metadata.model.EntityMetadata, java.lang.Object,
     * java.lang.String, java.util.List)
     */
    @Override
    public void writeData(String tableName, EntityMetadata m, Object entity, Object rowId,
            List<RelationHolder> relations) throws IOException
    {
        HTableInterface hTable = gethTable(tableName);

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());

        EntityType entityType = metaModel.entity(m.getEntityClazz());

        Set<Attribute> attributes = entityType.getAttributes();

        HBaseDataWrapper columnWrapper = new HBaseDataWrapper(rowId, new java.util.HashMap<String, Attribute>(),
                entity, null);
        List<HBaseDataWrapper> persistentData = new ArrayList<HBaseDataHandler.HBaseDataWrapper>(attributes.size());

        preparePersistentData(tableName, m.getTableName(), entity, rowId, metaModel, attributes, columnWrapper,
                persistentData);

        hbaseWriter.writeColumns(hTable, columnWrapper.getRowKey(), columnWrapper.getColumns(), entity,
                m.getTableName());

        for (HBaseDataWrapper wrapper : persistentData)
        {
            hbaseWriter.writeColumns(hTable, wrapper.getColumnFamily(), wrapper.getRowKey(), wrapper.getColumns(),
                    wrapper.getEntity());
        }

        // Persist relationships as a column in newly created Column family by
        // Kundera
        boolean containsEmbeddedObjectsOnly = columnWrapper.getColumns().isEmpty() && persistentData.isEmpty();

        if (relations != null && !relations.isEmpty())
        {
            hbaseWriter.writeRelations(hTable, rowId, containsEmbeddedObjectsOnly, relations, m.getTableName());
        }
        puthTable(hTable);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#writeJoinTableData(java.lang
     * .String, java.lang.String, java.util.Map)
     */
    @Override
    public void writeJoinTableData(String tableName, Object rowId, Map<String, Object> columns, String joinTableName)
            throws IOException
    {
        HTableInterface hTable = gethTable(tableName);

        hbaseWriter.writeColumns(hTable, rowId, columns, joinTableName);

        puthTable(hTable);

    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#getForeignKeysFromJoinTable
     * (java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public <E> List<E> getForeignKeysFromJoinTable(String joinTableName, Object rowKey, String inverseJoinColumnName)
    {
        List<E> foreignKeys = new ArrayList<E>();

        HTableInterface hTable = null;

        // Load raw data from Join Table in HBase
        try
        {
            hTable = gethTable(joinTableName);

            List<HBaseData> results = hbaseReader.LoadData(hTable, joinTableName, rowKey, getFilter(joinTableName));

            // assuming rowKey is not null.
            if (results != null)
            {

                HBaseData data = results.get(0);

                List<KeyValue> hbaseValues = data.getColumns();
                if (hbaseValues != null)
                {
                    for (KeyValue colData : hbaseValues)
                    {
                        String hbaseColumn = Bytes.toString(colData.getQualifier());
                        String hbaseColumnFamily = Bytes.toString(colData.getFamily());

                        if (hbaseColumnFamily.equals(joinTableName) && hbaseColumn.startsWith(inverseJoinColumnName))
                        {
                            byte[] val = colData.getValue();

                            // TODO : Because no attribute class is present, so
                            // cannot be done.
                            String hbaseColumnValue = Bytes.toString(val);

                            foreignKeys.add((E) hbaseColumnValue);
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            return foreignKeys;
        }
        finally
        {
            try
            {
                if (hTable != null)
                {
                    puthTable(hTable);
                }
            }
            catch (IOException e)
            {

                // Do nothing.
            }
        }
        return foreignKeys;
    }

    /**
     * Selects an HTable from the pool and returns.
     * 
     * @param tableName
     *            Name of HBase table
     * @return the h table
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public HTableInterface gethTable(final String tableName) throws IOException
    {
        return hTablePool.getTable(tableName);
    }

    /**
     * Puts HTable back into the HBase table pool.
     * 
     * @param hTable
     *            HBase Table instance
     */
    private void puthTable(HTableInterface hTable) throws IOException
    {
        hTablePool.putTable(hTable);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.client.hbase.admin.DataHandler#shutdown()
     */
    @Override
    public void shutdown()
    {

        // TODO: Shutting down admin actually shuts down HMaster, something we
        // don't want.
        // Devise a better way to release resources.

        /*
         * try {
         * 
         * admin.shutdown();
         * 
         * } catch (IOException e) { throw new RuntimeException(e.getMessage());
         * }
         */
    }

    // TODO: Scope of performance improvement in this method
    /**
     * Populate entity from hbase data.
     * 
     * @param entity
     *            the entity
     * @param hbaseData
     *            the hbase data
     * @param m
     *            the m
     * @param rowKey
     *            the row key
     * @param relationNames
     *            the relation names
     * @return the object
     */
    private Object populateEntityFromHbaseData(Object entity, HBaseData hbaseData, EntityMetadata m, Object rowKey,
            List<String> relationNames)
    {
        try
        {
            // Raw data retrieved from HBase for a particular row key (contains
            // all column families)
            List<KeyValue> hbaseValues = hbaseData.getColumns();

            Map<String, Object> relations = new HashMap<String, Object>();
            /*
             * Populate columns data
             */
            MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                    m.getPersistenceUnit());
            EntityType entityType = metaModel.entity(m.getEntityClazz());

            Set<Attribute> attributes = entityType.getAttributes();

            for (Attribute attribute : attributes)
            {
                Class javaType = ((AbstractAttribute) attribute).getBindableJavaType();
                String key = ((AbstractAttribute) attribute).getJPAColumnName();
                if (metaModel.isEmbeddable(javaType))
                {
                    EmbeddableType columnFamily = metaModel.embeddable(javaType);

                    Field columnFamilyFieldInEntity = (Field) attribute.getJavaMember();
                    Class<?> columnFamilyClass = columnFamilyFieldInEntity.getType();

                    // Get a name->field map for columns in this column family
                    Map<String, Field> columnNameToFieldMap = MetadataUtils.createColumnsFieldMap(m, columnFamily);

                    // Column family can be either @Embedded or
                    // @EmbeddedCollection
                    if (Collection.class.isAssignableFrom(columnFamilyClass))
                    {
                        Map<Integer, Object> elementCollectionObjects = new HashMap<Integer, Object>();

                        Field embeddedCollectionField = (Field) attribute.getJavaMember();
                        Object[] embeddedObjectArr = new Object[hbaseValues.size()];
                        Object embeddedObject = MetadataUtils.getEmbeddedGenericObjectInstance(embeddedCollectionField);
                        int prevColumnNameCounter = 0; // Previous Column name
                                                       // counter
                        for (KeyValue colData : hbaseValues)
                        {
                            String columnFamilyName = Bytes.toString(colData.getFamily());
                            String columnName = Bytes.toString(colData.getQualifier());
                            byte[] columnValue = colData.getValue();

                            if (columnName.startsWith(((AbstractAttribute) attribute).getJPAColumnName()))
                            {
                                String cfNamePostfix = MetadataUtils.getEmbeddedCollectionPostfix(columnName);
                                int cfNameCounter = Integer.parseInt(cfNamePostfix);
                                embeddedObject = elementCollectionObjects.get(cfNameCounter);
                                if (embeddedObject == null)
                                {
                                    embeddedObject = MetadataUtils
                                            .getEmbeddedGenericObjectInstance(embeddedCollectionField);
                                }

                                // Set Hbase data into the embedded object
                                setHBaseDataIntoObject(colData, columnFamilyFieldInEntity, columnNameToFieldMap,
                                        embeddedObject);

                                elementCollectionObjects.put(cfNameCounter, embeddedObject);
                            }
                            // Only populate those data from Hbase into entity
                            // that
                            // matches with column family name
                            // in the format <Collection field name>#<sequence
                            // count>

                            if (relationNames != null && relationNames.contains(columnFamilyName)
                                    && columnValue.length != 0)
                            {
                                relations.put(columnFamilyName,
                                        getObjectFromByteArray(entityType, columnValue, columnFamilyName, m));
                            }

                            // String cfNamePostfix =
                            // MetadataUtils.getEmbeddedCollectionPostfix(columnName);
                            // int cfNameCounter =
                            // Integer.parseInt(cfNamePostfix);
                            // if (cfNameCounter != prevColumnNameCounter)
                            // {
                            // prevColumnNameCounter = cfNameCounter;
                            //
                            // // Fresh embedded object for the next column
                            // // family
                            // // in collection
                            // embeddedObject = MetadataUtils
                            // .getEmbeddedGenericObjectInstance(embeddedCollectionField);
                            // }

                            // Set Hbase data into the embedded object
                            // setHBaseDataIntoObject(colData,
                            // columnFamilyFieldInEntity, columnNameToFieldMap,
                            // embeddedObject);

                            // embeddedObjectArr[cfNameCounter] =
                            // embeddedObject;

                            // Save embedded object into Cache, needed while
                            // updation and deletion
                            ElementCollectionCacheManager.getInstance().addElementCollectionCacheMapping(rowKey,
                                    embeddedObject, columnFamilyName);
                        }

                        for(Integer integer : elementCollectionObjects.keySet())
                        {
                            embeddedObjectArr[integer] = elementCollectionObjects.get(integer);
                        }
                        // Collection to hold column family objects
                        Collection embeddedCollection = MetadataUtils
                                .getEmbeddedCollectionInstance(embeddedCollectionField);
                        embeddedCollection.addAll(Arrays.asList(embeddedObjectArr));
                        embeddedCollection.removeAll(Collections.singletonList(null));
                        embeddedObjectArr = null; // Eligible for GC

                        // Now, set the embedded collection into entity
                        if (embeddedCollection != null && !embeddedCollection.isEmpty())
                        {
                            PropertyAccessorHelper.set(entity, embeddedCollectionField, embeddedCollection);
                        }
                    }
                    else
                    {
                        Object columnFamilyObj = columnFamilyClass.newInstance();

                        for (KeyValue colData : hbaseValues)
                        {
                            String cfInHbase = Bytes.toString(colData.getFamily());

                            byte[] columnValue = colData.getValue();
                            if (relationNames != null && relationNames.contains(cfInHbase) && columnValue.length != 0)
                            {
                                relations.put(cfInHbase, getObjectFromByteArray(entityType, columnValue, cfInHbase, m));
                            }
                            // Set Hbase data into the column family object

                            String colName = Bytes.toString(colData.getQualifier());

                            // Get Column from metadata
                            Field columnField = columnNameToFieldMap.get(colName);
                            if (columnField != null && columnValue.length != 0)
                            {
                                if (columnFamilyFieldInEntity.isAnnotationPresent(Embedded.class)
                                        || columnFamilyFieldInEntity.isAnnotationPresent(ElementCollection.class))
                                {
                                    PropertyAccessorHelper.set(columnFamilyObj, columnField,
                                            HBaseUtils.fromBytes(columnValue, columnField.getType()));
                                }
                                else
                                {
                                    columnFamilyObj = getObjectFromByteArray(entityType, columnValue, cfInHbase, m);
                                }
                            }
                        }
                        PropertyAccessorHelper.set(entity, columnFamilyFieldInEntity, columnFamilyObj);
                    }
                }
                else if (!attribute.getName().equals(m.getIdAttribute().getName()))
                {
                    Field columnField = (Field) attribute.getJavaMember();
                    String columnName = ((AbstractAttribute) attribute).getJPAColumnName();

                    for (KeyValue colData : hbaseValues)
                    {
                        String hbaseColumn = Bytes.toString(colData.getQualifier());
                        String colName = hbaseColumn;
                        byte[] columnValue = colData.getValue();
                        if (relationNames != null && relationNames.contains(colName) && columnValue.length != 0)
                        {
                            relations.put(colName, getObjectFromByteArray(entityType, columnValue, colName, m));
                        }
                        else if (colName != null && colName.equalsIgnoreCase(columnName.toLowerCase())
                                && columnValue.length != 0)
                        {
                            PropertyAccessorHelper.set(entity, columnField,
                                    HBaseUtils.fromBytes(columnValue, columnField.getType()));
                        }
                    }
                }
            }
            if (!relations.isEmpty())
            {
                return new EnhanceEntity(entity, rowKey, relations);
            }
            return entity;
        }
        catch (PropertyAccessException e1)
        {
            throw new RuntimeException(e1);
        }
        catch (InstantiationException e1)
        {
            throw new RuntimeException(e1);
        }
        catch (IllegalAccessException e1)
        {
            throw new RuntimeException(e1);
        }
    }

    /**
     * Sets the h base data into object.
     * 
     * @param colData
     *            the col data
     * @param columnFamilyField
     *            the column family field
     * @param columnNameToFieldMap
     *            the column name to field map
     * @param columnFamilyObj
     *            the column family obj
     * @throws PropertyAccessException
     *             the property access exception
     */
    private void setHBaseDataIntoObject(KeyValue colData, Field columnFamilyField,
            Map<String, Field> columnNameToFieldMap, Object columnFamilyObj) throws PropertyAccessException
    {
        String colName = Bytes.toString(colData.getQualifier());
        String qualifier = colName.substring(colName.indexOf("#")+1, colName.lastIndexOf("#"));
        byte[] columnValue = colData.getValue();

        // Get Column from metadata
        Field columnField = columnNameToFieldMap.get(qualifier);
        if (columnField != null)
        {
            if (columnFamilyField.isAnnotationPresent(Embedded.class)
                    || columnFamilyField.isAnnotationPresent(ElementCollection.class))
            {
                PropertyAccessorHelper.set(columnFamilyObj, columnField, columnValue);
            }
            else
            {
                columnFamilyObj = HBaseUtils.fromBytes(columnValue, columnFamilyObj.getClass());
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.client.hbase.admin.DataHandler#deleteRow(java.lang.String,
     * java.lang.String)
     */
    public void deleteRow(Object rowKey, String tableName, String columnFamilyName) throws IOException
    {
        hbaseWriter.delete(gethTable(tableName), rowKey, columnFamilyName);
    }

    @Override
    public List<Object> findParentEntityFromJoinTable(EntityMetadata parentMetadata, String joinTableName,
            String joinColumnName, String inverseJoinColumnName, Object childId)
    {
        throw new PersistenceException("Not applicable for HBase");
    }

    /**
     * Set filter to data handler.
     * 
     * @param filter
     *            hbase filter.
     */
    public void setFilter(Filter filter)
    {
        if (this.filter == null)
        {
            this.filter = new FilterList();
        }
        if (filter != null)
        {
            this.filter.addFilter(filter);
        }
    }

    public void addFilter(final String columnFamily, Filter filter)
    {
        FilterList filterList = this.filters.get(columnFamily);
        if (filterList == null)
        {
            filterList = new FilterList();
        }
        if (filter != null)
        {
            filterList.addFilter(filter);
        }
        this.filters.put(columnFamily, filterList);
    }

    /**
     * 
     * @param tableName
     * @param clazz
     * @param m
     * @param startRow
     * @param endRow
     * @param output
     * @param hTable
     * @param entity
     * @param relationNames
     * @param results
     * @return
     * @throws IOException
     */
    private List onRead(String tableName, Class clazz, EntityMetadata m, List output, HTableInterface hTable,
            Object entity, List<String> relationNames, List<HBaseData> results) throws IOException
    {
        try
        {
            // Populate raw data from HBase into entity

            if (results != null)
            {
                for (HBaseData data : results)
                {
                    entity = clazz.newInstance(); // Entity Object
                    /* Set Row Key */
                    PropertyAccessorHelper.setId(entity, m, HBaseUtils.fromBytes(m, data.getRowKey()));

                    if (data.getColumns() != null)
                    {
                        entity = populateEntityFromHbaseData(entity, data, m, null, relationNames);
                        if (output == null)
                        {
                            output = new ArrayList();
                        }
                    }
                    output.add(entity);
                }
            }
        }
        catch (InstantiationException iex)
        {
            log.error("Error while creating an instance of {} .", clazz);
            throw new PersistenceException(iex);
        }
        catch (IllegalAccessException iaex)
        {
            log.error("Illegal Access while reading data from {}, Caused by: .", tableName, iaex);
            throw new PersistenceException(iaex);
        }
        catch (Exception e)
        {
            log.error("Error while creating an instance of {}, Caused by: .", clazz, e);
            throw new PersistenceException(e);
        }
        finally
        {
            if (hTable != null)
            {
                puthTable(hTable);
            }
        }
        return output;
    }

    /**
     * @author vivek.mishra
     * 
     */
    public static class HBaseDataWrapper
    {
        private Object rowKey;

        // private Set<Attribute> columns;

        private Map<String, Attribute> columns;

        private Object entity;

        private String columnFamily;

        /**
         * @param rowKey
         * @param columns
         * @param entity
         * @param columnFamily
         */
        public HBaseDataWrapper(Object rowKey, Map<String, Attribute> columns, Object entity, String columnFamily)
        {
            super();
            this.rowKey = rowKey;
            this.columns = columns;
            this.entity = entity;
            this.columnFamily = columnFamily;
        }

        /**
         * @return the rowKey
         */
        public Object getRowKey()
        {
            return rowKey;
        }

        /**
         * @return the columns
         */
        public Map<String, Attribute> getColumns()
        {
            return columns;
        }

        /**
         * @return the entity
         */
        public Object getEntity()
        {
            return entity;
        }

        /**
         * @return the columnFamily
         */
        public String getColumnFamily()
        {
            return columnFamily;
        }

        public void addColumn(String columnName, Attribute column)
        {
            columns.put(columnName, column);
        }
    }

    public List scanData(Filter f, final String tableName, Class clazz, EntityMetadata m, String columnFamily,
            String qualifier) throws IOException, InstantiationException, IllegalAccessException
    {
        List returnedResults = new ArrayList();
        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                m.getPersistenceUnit());
        EntityType entityType = metaModel.entity(m.getEntityClazz());
        Set<Attribute> attributes = entityType.getAttributes();
        String[] columns = new String[attributes.size() - 1];
        int count = 0;
        boolean isCollection = false;
        for (Attribute attr : attributes)
        {
            if (!attr.isCollection() && !attr.getName().equalsIgnoreCase(m.getIdAttribute().getName()))
            {
                columns[count++] = ((AbstractAttribute) attr).getJPAColumnName();
            }
            else if (attr.isCollection())
            {
                isCollection = true;
                break;
            }
        }
        List<HBaseData> results = hbaseReader.loadAll(gethTable(tableName), f, null, null, m.getTableName(),
                isCollection ? qualifier : null, null);
        if (results != null)
        {
            for (HBaseData row : results)
            {
                Object entity = clazz.newInstance();// Entity Object
                /* Set Row Key */
                PropertyAccessorHelper.setId(entity, m, HBaseUtils.fromBytes(m, row.getRowKey()));

                returnedResults.add(populateEntityFromHbaseData(entity, row, m, row.getRowKey(), m.getRelationNames()));
            }
        }
        return returnedResults;
    }

    @Override
    public Object[] scanRowyKeys(FilterList filterList, String tableName, String columnFamilyName, String columnName,
            final Class rowKeyClazz) throws IOException
    {
        HTableInterface hTable = null;
        hTable = gethTable(tableName);
        return hbaseReader.scanRowKeys(hTable, filterList, columnFamilyName, columnName, rowKeyClazz);
    }

    private Object getObjectFromByteArray(EntityType entityType, byte[] value, String jpaColumnName, EntityMetadata m)
    {
        if (jpaColumnName != null)
        {
            String fieldName = m.getFieldName(jpaColumnName);
            if (fieldName != null)
            {
                Attribute attribute = fieldName != null ? entityType.getAttribute(fieldName) : null;

                EntityMetadata relationMetadata = KunderaMetadataManager.getEntityMetadata(attribute.getJavaType());
                Object colValue = PropertyAccessorHelper.getObject(relationMetadata.getIdAttribute().getJavaType(),
                        (byte[]) value);
                return colValue;
            }
        }
        log.warn("No value found for column {}, returning null.", jpaColumnName);
        return null;
    }

    /**
     * 
     * @param tableName
     * @param entity
     * @param rowId
     * @param metaModel
     * @param attributes
     * @param columnWrapper
     * @param persistentData
     * @return
     * @throws IOException
     */
    public void preparePersistentData(String tableName, String columnFamily, Object entity, Object rowId,
            MetamodelImpl metaModel, Set<Attribute> attributes, HBaseDataWrapper columnWrapper,
            List<HBaseDataWrapper> persistentData) throws IOException
    {
        for (Attribute column : attributes)
        {
            String fieldName = ((AbstractAttribute) column).getJPAColumnName();

            Class javaType = ((AbstractAttribute) column).getBindableJavaType();
            if (metaModel.isEmbeddable(javaType))
            {
                String columnFamilyName = ((AbstractAttribute) column).getJPAColumnName();
                Field columnFamilyField = (Field) column.getJavaMember();
                Object columnFamilyObject = null;
                try
                {
                    columnFamilyObject = PropertyAccessorHelper.getObject(entity, columnFamilyField);
                }
                catch (PropertyAccessException paex)
                {
                    log.error("Error while getting {}, field from entity {} .", columnFamilyName, entity);
                    throw new KunderaException(paex);
                }

                if (columnFamilyObject != null)
                {
                    // continue;
                    Set<Attribute> columns = metaModel.embeddable(javaType).getAttributes();
                    Map<String, Attribute> columnNameToAttribute = new HashMap<String, Attribute>();
                    if (column.isCollection())
                    {
                        String dynamicCFName = null;

                        ElementCollectionCacheManager ecCacheHandler = ElementCollectionCacheManager.getInstance();
                        // Check whether it's first time insert or updation
                        if (ecCacheHandler.isCacheEmpty())
                        { // First time insert
                            int count = 0;
                            for (Object obj : (Collection) columnFamilyObject)
                            {
                                // dynamicCFName = columnFamilyName +
                                // Constants.EMBEDDED_COLUMN_NAME_DELIMITER +
                                // count;

                                for (Attribute attribute : columns)
                                {
                                    columnNameToAttribute.put(columnFamilyName
                                            + Constants.EMBEDDED_COLUMN_NAME_DELIMITER
                                            + ((AbstractAttribute) attribute).getJPAColumnName()
                                            + Constants.EMBEDDED_COLUMN_NAME_DELIMITER + count, attribute);
                                }
                                // addColumnFamilyToTable(tableName,
                                // dynamicCFName);
                                // prepare column name for @ElementCollection
                                // columns.

                                persistentData
                                        .add(new HBaseDataWrapper(rowId, columnNameToAttribute, obj, columnFamily));
                                count++;
                            }
                        }
                        else
                        {
                            // Updation
                            // Check whether this object is already in cache,
                            // which
                            // means we already have a column family with that
                            // name
                            // Otherwise we need to generate a fresh column
                            // family
                            // name
                            int lastEmbeddedObjectCount = ecCacheHandler.getLastElementCollectionObjectCount(rowId);
                            for (Object obj : (Collection) columnFamilyObject)
                            {
                                dynamicCFName = ecCacheHandler.getElementCollectionObjectName(rowId, obj);
                                if (dynamicCFName == null)
                                { // Fresh row
                                  // dynamicCFName = columnFamilyName +
                                  // Constants.EMBEDDED_COLUMN_NAME_DELIMITER
                                  // + (++lastEmbeddedObjectCount);
                                    for (Attribute attribute : columns)
                                    {
                                        columnNameToAttribute.put(columnFamilyName
                                                + Constants.EMBEDDED_COLUMN_NAME_DELIMITER
                                                + ((AbstractAttribute) attribute).getJPAColumnName()
                                                + Constants.EMBEDDED_COLUMN_NAME_DELIMITER
                                                + (++lastEmbeddedObjectCount), attribute);
                                    }
                                }
                                // addColumnFamilyToTable(tableName,
                                // dynamicCFName);
                                persistentData
                                        .add(new HBaseDataWrapper(rowId, columnNameToAttribute, obj, columnFamily));
                            }
                            // Clear embedded collection cache for GC
                            ecCacheHandler.clearCache();
                        }
                    }
                    else
                    {
                        // Write Column family which was Embedded object in
                        // entity
                        for (Attribute attribute : columns)
                        {
                            columnNameToAttribute.put(((AbstractAttribute) attribute).getJPAColumnName(), attribute);
                        }

                        if (columnFamilyField.isAnnotationPresent(Embedded.class))
                        {
                            persistentData.add(new HBaseDataWrapper(rowId, columnNameToAttribute, columnFamilyObject,
                                    columnFamily));
                        }
                        else
                        {
                            persistentData.add(new HBaseDataWrapper(rowId, columnNameToAttribute, columnFamilyObject,
                                    columnFamily));
                        }
                    }
                }
            }
            else if (!column.isAssociation())
            {
                columnWrapper.addColumn(((AbstractAttribute) column).getJPAColumnName(), column);
            }
        }
    }

    /**
     * @param data
     * @throws IOException
     */
    public void batch_insert(Map<HTableInterface, List<HBaseDataWrapper>> data) throws IOException
    {
        hbaseWriter.persistRows(data);
    }

    public void setFetchSize(final int fetchSize)
    {
        ((HBaseReader) hbaseReader).setFetchSize(fetchSize);
    }

    public Object next(EntityMetadata m)
    {
        Object entity = null;
        HBaseData result = ((HBaseReader) hbaseReader).next();
        List<HBaseData> results = new ArrayList<HBaseData>();
        List output = new ArrayList();
        results.add(result);
        try
        {
            output = onRead(m.getSchema(), m.getEntityClazz(), m, output, gethTable(m.getSchema()), entity,
                    m.getRelationNames(), results);
        }
        catch (IOException e)
        {
            log.error("Error during finding next record, Caused by: .", e);
            throw new KunderaException(e);
        }

        return output != null && !output.isEmpty() ? output.get(0) : output;
    }

    // public List next(EntityMetadata m, final int chunkSize)
    // {
    // Object entity = null;
    // List<HBaseData> results = ((HBaseReader) hbaseReader).next(chunkSize);
    // List output = new ArrayList();
    // try
    // {
    // output = onRead(m.getTableName(), m.getEntityClazz(), m, output,
    // gethTable(m.getTableName()), entity,
    // m.getRelationNames(), results);
    // }
    // catch (IOException e)
    // {
    // log.error("Error during finding next record, Caused by: .", e);
    // throw new KunderaException(e);
    // }
    // return output != null ? output : new ArrayList();
    // }

    public boolean hasNext()
    {
        return ((HBaseReader) hbaseReader).hasNext();
    }

    public void reset()
    {
        resetFilter();
        ((HBaseReader) hbaseReader).reset();
    }

    public void resetFilter()
    {
        filter = null;
        filters = new ConcurrentHashMap<String, FilterList>();
    }

    public HBaseDataHandler getHandle()
    {
        HBaseDataHandler handler = new HBaseDataHandler(this.conf, this.hTablePool);
        handler.filter = this.filter;
        handler.filters = this.filters;
        return handler;
    }

    private Filter getFilter(final String columnFamily)
    {
        FilterList filter = filters.get(columnFamily);
        if (filter == null)
        {
            return this.filter;
        }
        if (this.filter != null)
        {
            filter.addFilter(this.filter);
        }
        return filter;
    }
}
