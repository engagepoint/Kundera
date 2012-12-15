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
package com.impetus.client.redis;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.PersistenceException;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisConnectionException;

import com.impetus.client.redis.query.RedisQuery;
import com.impetus.kundera.Constants;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.ClientBase;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.graph.Node;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.persistence.EntityReader;
import com.impetus.kundera.persistence.api.Batcher;
import com.impetus.kundera.persistence.context.jointable.JoinTableData;
import com.impetus.kundera.property.PropertyAccessorHelper;

/**
 * @author vivek.mishra
 * 
 * 
 *         TODOOOOOOO ::: 1) Embedded handling 2) Composite key 3) Association
 *         handling
 * 
 */
public class RedisClient extends ClientBase implements Client<RedisQuery>, Batcher
{
    /**
     * Reference to redis client factory.
     */
    private RedisClientFactory factory;

    /** The logger. */
    private static Logger logger = LoggerFactory.getLogger(RedisClient.class);

    RedisClient(final RedisClientFactory factory)
    {
        this.factory = factory;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.client.ClientBase#onPersist(com.impetus.kundera.metadata
     * .model.EntityMetadata, java.lang.Object, java.lang.Object,
     * java.util.List)
     */
    @Override
    protected void onPersist(EntityMetadata entityMetadata, Object entity, Object id, List<RelationHolder> rlHolders)
    {
        Jedis connection = factory.getConnection();
        try
        {

            // first open a pipeline
            // Create a hashset and populate data into it

            Pipeline pipeLine = connection.pipelined();
            AttributeWrapper wrapper = wrap(entityMetadata, entity);

            String rowKey = PropertyAccessorHelper.getString(entity, (Field) entityMetadata.getIdAttribute()
                    .getJavaMember());

            String hashKey = getHashKey(entityMetadata, rowKey);

            connection.hmset(getEncodedBytes(hashKey), wrapper.getColumns());

            // Add inverted indexes for column based search.
            addIndex(connection, wrapper, rowKey);
            //
            pipeLine.sync(); // send I/O.. as persist call. so no need to read
                             // response?
        }
        finally
        {
            factory.releaseConnection(connection);
        }

    }

    @Override
    public Object find(Class entityClass, Object key)
    {
        Object result = null;
        Jedis connection = factory.getConnection();
        try
        {
            result = fetch(entityClass, key, connection);

        }
        catch (InstantiationException e)
        {
            logger.error("Error during find by key:", e);
            throw new PersistenceException(e);
        }
        catch (IllegalAccessException e)
        {
            logger.error("Error during find by key:", e);
            throw new PersistenceException(e);
        }
        finally
        {
            factory.releaseConnection(connection);
        }

        return result;
    }

    private Object fetch(Class clazz, Object key, Jedis connection) throws InstantiationException,
            IllegalAccessException
    {
        Object result;
        // byte[] rowKey = PropertyAccessorHelper.getBytes(key);

        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(clazz);
        String rowKey = getHashKey(entityMetadata, PropertyAccessorHelper.getString(key));

        try
        {
            Map<byte[], byte[]> columns = connection.hgetAll(getEncodedBytes(rowKey));
            result = unwrap(entityMetadata, columns);
        }
        catch (JedisConnectionException jedex)
        {
            // Jedis is throwing runtime exception in case of no result found!!!!
            return null;
        }

        if (result != null)
        {
            PropertyAccessorHelper.set(result, (Field) entityMetadata.getIdAttribute().getJavaMember(), key);
        }

        return result;
    }

    @Override
    public <E> List<E> findAll(Class<E> entityClass, Object... keys)
    {
        Jedis connection = factory.getConnection();
        connection.pipelined();
        List results = new ArrayList();
        try
        {
            for (Object key : keys)
            {
                Object result = fetch(entityClass, key, connection);
                if (result != null)
                {
                    results.add(result);
                }
            }
        }
        catch (InstantiationException e)
        {
            logger.error("Error during find by key:", e);
            throw new PersistenceException(e);
        }
        catch (IllegalAccessException e)
        {
            logger.error("Error during find by key:", e);
            throw new PersistenceException(e);
        }
        return results;
    }

    @Override
    public <E> List<E> find(Class<E> entityClass, Map<String, String> embeddedColumnMap)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void close()
    {

    }

    @Override
    public void delete(Object entity, Object pKey)
    {
        Jedis connection = factory.getConnection();
        try
        {
            Pipeline pipeLine = connection.pipelined();

            EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(entity.getClass());
            AttributeWrapper wrapper = wrap(entityMetadata, entity);

            Set<byte[]> columnNames = wrapper.columns.keySet();

            String rowKey = PropertyAccessorHelper.getString(entity, (Field) entityMetadata.getIdAttribute()
                    .getJavaMember());

            for (byte[] name : columnNames)
            {
                connection.hdel(getHashKey(entityMetadata, rowKey), rowKey);
            }

            // Delete inverted indexes.
            unIndex(connection, wrapper, rowKey);

            connection.sync();
        }
        finally
        {
            factory.releaseConnection(connection);
        }
    }

    @Override
    public void persistJoinTable(JoinTableData joinTableData)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public <E> List<E> getColumnsById(String schemaName, String tableName, String pKeyColumnName, String columnName,
            Object pKeyColumnValue)
    {
        // TODO Auto-generated method stub
        return null;
    }

    // @Override
    // public Object[] findIdsByColumn(String tableName, String pKeyName, String
    // columnName, Object columnValue,
    // Class entityClazz)
    // {
    // // TODO Auto-generated method stub
    // return null;
    // }

    @Override
    public Object[] findIdsByColumn(String schemaName, String tableName, String pKeyName, String columnName,
            Object columnValue, Class entityClazz)
    {
        // TODO Auto-generated method stub
        return null;
    }

    // @Override
    // public void deleteByColumn(String tableName, String columnName, Object
    // columnValue)
    // {
    // // TODO Auto-generated method stub
    //
    // }

    @Override
    public void deleteByColumn(String schemaName, String tableName, String columnName, Object columnValue)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public List<Object> findByRelation(String colName, Object colValue, Class entityClazz)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public EntityReader getReader()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Class<RedisQuery> getQueryImplementor()
    {
        return RedisQuery.class;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.persistence.api.Batcher#addBatch(com.impetus.kundera
     * .graph.Node)
     */
    @Override
    public void addBatch(Node node)
    {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#executeBatch()
     */
    @Override
    public int executeBatch()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#getBatchSize()
     */
    @Override
    public int getBatchSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    private AttributeWrapper wrap(EntityMetadata entityMetadata, Object entity)
    {

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                entityMetadata.getPersistenceUnit());

        EntityType entityType = metaModel.entity(entityMetadata.getEntityClazz());
        Set<Attribute> attributes = entityType.getAttributes();

        // attributes can be null??? i guess NO
        AttributeWrapper wrapper = new AttributeWrapper(attributes.size());

        // PropertyAccessorHelper.get(entity,
        for (Attribute attr : attributes)
        {
            if (!entityMetadata.getIdAttribute().equals(attr))
            {
                if (metaModel.isEmbeddable(((AbstractAttribute) attr).getBindableJavaType()))
                {
                    // TODO:::::: process on embeddables.
                }
                else
                {
                    byte[] value = PropertyAccessorHelper.get(entity, (Field) attr.getJavaMember());
                    String valueAsStr = PropertyAccessorHelper.getString(entity, (Field) attr.getJavaMember());
                    byte[] name;
                    name = getEncodedBytes(((AbstractAttribute) attr).getJPAColumnName());

                    // add column name as key and value as value
                    wrapper.addColumn(name, value);
                    // // {tablename:columnname,hashcode} for value
                    wrapper.addIndex(getHashKey(entityMetadata, ((AbstractAttribute) attr).getJPAColumnName()),
                            Double.parseDouble(((Integer) valueAsStr.hashCode()).toString()));
                }
            }
            else if (attributes.size() == 1) // means it is only a key! weird
                                             // but possible negative scenario
            {
                byte[] value = PropertyAccessorHelper.get(entity, (Field) attr.getJavaMember());
                byte[] name;
                name = getEncodedBytes(((AbstractAttribute) attr).getJPAColumnName());

                // add column name as key and value as value
                wrapper.addColumn(name, value);

            }
        }

        return wrapper;

    }

    private Object unwrap(EntityMetadata entityMetadata, Map<byte[], byte[]> results) throws InstantiationException,
            IllegalAccessException
    {

        MetamodelImpl metaModel = (MetamodelImpl) KunderaMetadata.INSTANCE.getApplicationMetadata().getMetamodel(
                entityMetadata.getPersistenceUnit());

        List<String> relationNames = entityMetadata.getRelationNames();
        EntityType entityType = metaModel.entity(entityMetadata.getEntityClazz());

        Object entity = null;
        // Set<Attribute> attributes = entityType.getAttributes();

        Set<byte[]> columnNames = results.keySet();
        for (byte[] nameInByte : columnNames)
        {
            if (entity == null)
            {
                entity = entityMetadata.getEntityClazz().newInstance();
            }

            String columnName = new String(nameInByte);
            byte[] value = results.get(nameInByte);
            String fieldName = entityMetadata.getFieldName(columnName);

            Attribute attribute = entityType.getAttribute(fieldName);

            // this will set field into entity.
            // TODO: :: what about crap relation and embedded attribute
            PropertyAccessorHelper.set(entity, (Field) attribute.getJavaMember(), value);
        }

        return entity;

    }

    // Indexer interface needs to be carved out for such stuff

    private class RedisIndexer
    {
    }

    private class AttributeWrapper
    {
        private Map<byte[], byte[]> columns;

        private Map<String, Double> indexes;

        public AttributeWrapper()
        {
            columns = new HashMap<byte[], byte[]>();

            indexes = new HashMap<String, Double>();
        }

        /**
         * @param columns
         * @param indexes
         */
        AttributeWrapper(int size)
        {
            columns = new HashMap<byte[], byte[]>(size);

            indexes = new HashMap<String, Double>(size);
        }

        private void addColumn(byte[] key, byte[] value)
        {
            columns.put(key, value);
        }

        private void addIndex(String key, Double score)
        {
            indexes.put(key, score);
        }

        Map<byte[], byte[]> getColumns()
        {
            return columns;
        }

        Map<String, Double> getIndexes()
        {
            return indexes;
        }

    }

    private String getHashKey(final EntityMetadata entityMetadata, final String rowKey)
    {
        StringBuilder builder = new StringBuilder(entityMetadata.getTableName());
        builder.append(":");
        builder.append(rowKey);
        return builder.toString();
    }

    private byte[] getEncodedBytes(final String name)
    {
        try
        {

            if (name != null)
            {
                return name.getBytes(Constants.CHARSET_UTF8);
            }
        }
        catch (UnsupportedEncodingException e)
        {
            // TODO :LOGGGINNNNNGGGGGGG!.
            // throw an error.
        }

        return null;
    }

    private void addIndex(final Jedis connection, final AttributeWrapper wrapper, final String rowKey)
    {
        Set<String> indexKeys = wrapper.getIndexes().keySet();

        for (String idx_Name : indexKeys)
        {
            connection.zadd(idx_Name, wrapper.getIndexes().get(idx_Name), rowKey);
        }
    }

    private void unIndex(final Jedis connection, final AttributeWrapper wrapper, final String rowKey)
    {
        Set<String> members = wrapper.getIndexes().keySet();
        for (String member : members)
        {
            connection.zrem(member, rowKey);
        }
    }

}
