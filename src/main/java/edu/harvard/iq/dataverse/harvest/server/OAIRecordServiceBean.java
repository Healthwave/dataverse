/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse.harvest.server;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.export.ExportException;
import edu.harvard.iq.dataverse.export.ExportService;
import edu.harvard.iq.dataverse.search.IndexServiceBean;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import static javax.ejb.TransactionAttributeType.REQUIRES_NEW;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

/**
 *
 * @author Leonid Andreev
 * based on the implementation of "HarvestStudyServiceBean" from
 * DVN 3*, by Gustavo Durand. 
 */

@Stateless
@Named
public class OAIRecordServiceBean implements java.io.Serializable {
    @EJB 
    OAISetServiceBean oaiSetService;    
    @EJB 
    IndexServiceBean indexService;
    @EJB 
    DatasetServiceBean datasetService;
    //@EJB
    //ExportService exportService;

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    EntityManager em;   
    
    private static final Logger logger = Logger.getLogger("edu.harvard.iq.dataverse.harvest.server.OAIRecordServiceBean");
    
    /*
    public void updateOaiRecords() {
        Date updateTime = new Date();
        List<OAISet> sets = oaiSetService.findAll();
        
        for (OAISet oaiSet : sets) {
            List<Long> studyIds = indexService.query(oaiSet.getDefinition());
            studyIds = studyService.getVisibleStudies(studyIds, null);
            studyIds = studyService.getViewableStudies(studyIds);
            updateOaiRecords( oaiSet.getSpec(), studyIds, updateTime );
        }
        
        // also do noset membet
        List<Long> studyIds = studyService.getAllNonHarvestedStudyIds();
        studyIds = studyService.getVisibleStudies(studyIds, null);
        studyIds = studyService.getViewableStudies(studyIds);        
        updateOaiRecords( null, studyIds, updateTime );
        
    }   */ 

    public void updateOaiRecords(String setName, List<Long> datasetIds, Date updateTime, boolean doExport) {

        // create Map of OaiRecords
        List<OAIRecord> oaiRecords = findOaiRecordsBySetName( setName );
        Map<String,OAIRecord> recordMap = new HashMap();
        if (oaiRecords != null) {
            for (OAIRecord record : oaiRecords) {
                recordMap.put(record.getGlobalId(), record);
            }
        } else {
            logger.info("Null returned - no records found.");
        } 

        if (!recordMap.isEmpty()) {
            logger.info("Found "+recordMap.size()+" existing records");
        } else {
            logger.info("No records in the set yet.");
        }

        if (datasetIds != null) {
            for (Long datasetId : datasetIds) {
                logger.info("processing dataset id=" + datasetId);
                Dataset dataset = datasetService.find(datasetId);
                if (dataset == null) {
                    logger.info("failed to find dataset!");
                } else {
                    logger.info("found dataset.");

                    // TODO: option to *force* export? 
                    if (doExport) {
                        // TODO: 
                        // Review this logic - specifically for handling of 
                        // deaccessioned datasets. -- L.A. 4.5
                        if (dataset.getPublicationDate() != null
                                && (dataset.getLastExportTime() == null
                                || dataset.getLastExportTime().before(dataset.getPublicationDate()))) {
                            logger.info("Attempting to run export on dataset " + dataset.getGlobalId());
                            exportAllFormats(dataset);
                        }
                    }

                    logger.info("\"last exported\" timestamp: " + dataset.getLastExportTime());
                    em.refresh(dataset);
                    logger.info("\"last exported\" timestamp, after db refresh: " + dataset.getLastExportTime());

                    updateOaiRecordForDataset(dataset, setName, recordMap);
                }
            }
        }

        // anything left in the map should be marked as removed!
        markOaiRecordsAsRemoved( recordMap.values(), updateTime);
        
    }
    
    // This method updates -  creates/refreshes/un-marks-as-deleted - one OAI 
    // record at a time. It does so inside its own transaction, to ensure that 
    // the changes take place immediately. (except the method is called from 
    // reight here, in this EJB - so the attribute does not do anything! (TODO:!)
    @TransactionAttribute(REQUIRES_NEW)
    public void updateOaiRecordForDataset(Dataset dataset, String setName, Map<String, OAIRecord> recordMap) {
        // TODO: review .isReleased() logic
        if (dataset.isReleased() && dataset.getLastExportTime() != null) {
            OAIRecord record = recordMap.get(dataset.getGlobalId());
            if (record == null) {
                logger.info("creating a new OAI Record for " + dataset.getGlobalId());
                record = new OAIRecord(setName, dataset.getGlobalId(), new Date());
                em.persist(record);
            } else {
                if (record.isRemoved()) {
                    logger.info("\"un-deleting\" an existing OAI Record for " + dataset.getGlobalId());
                    record.setRemoved(false);
                    record.setLastUpdateTime(new Date());
                } else if (dataset.getLastExportTime().after(record.getLastUpdateTime())) {
                    logger.info("updating the timestamp on an existing record.");
                    record.setLastUpdateTime(new Date());
                }

                recordMap.remove(record.getGlobalId());
            }
        }
    }
    
    public void exportAllFormats(Dataset dataset) {
        try {
            ExportService exportServiceInstance = ExportService.getInstance();
            logger.fine("Attempting to run export on dataset "+dataset.getGlobalId());
            exportServiceInstance.exportAllFormats(dataset);
            datasetService.updateLastExportTimeStamp(dataset.getId());
        } catch (ExportException ee) {logger.fine("Caught export exception while trying to export. (ignoring)");}
        catch (Exception e) {logger.info("Caught unknown exception while trying to export (ignoring)");}
    }
    
    @TransactionAttribute(REQUIRES_NEW)
    public void exportAllFormatsInNewTransaction(Dataset dataset) {
        exportAllFormats(dataset);
    }
    
    public void markOaiRecordsAsRemoved(Collection<OAIRecord> records, Date updateTime) {
        for (OAIRecord oaiRecord : records) {
            if ( !oaiRecord.isRemoved() ) {
                logger.info("marking OAI record "+oaiRecord.getGlobalId()+" as removed");
                oaiRecord.setRemoved(true);
                oaiRecord.setLastUpdateTime(updateTime);
            } else {
                logger.info("OAI record "+oaiRecord.getGlobalId()+" is already marked as removed.");
            }
        }
       
    }
    
    
    public OAIRecord findOAIRecordBySetNameandGlobalId(String setName, String globalId) {
        OAIRecord oaiRecord = null;
        
        String queryString = "SELECT object(h) from OAIRecord h where h.globalId = :globalId";
        queryString += setName != null ? " and h.setName = :setName" : ""; // and h.setName is null";
        
        logger.fine("findOAIRecordBySetNameandGlobalId; query: "+queryString+"; globalId: "+globalId+"; setName: "+setName);
                
        
        Query query = em.createQuery(queryString).setParameter("globalId",globalId);
        if (setName != null) { query.setParameter("setName",setName); }        
        
        try {
           oaiRecord = (OAIRecord) query.setMaxResults(1).getSingleResult();
        } catch (javax.persistence.NoResultException e) {
           // Do nothing, just return null. 
        }
        logger.fine("returning oai record.");
        return oaiRecord;       
    }
    
    public List<OAIRecord> findOaiRecordsByGlobalId(String globalId) {
        String query="SELECT h from OAIRecord as h where h.globalId = :globalId";
        List<OAIRecord> oaiRecords = null;
        try {
            oaiRecords = em.createQuery(query).setParameter("globalId",globalId).getResultList();
        } catch (Exception ex) {
            // Do nothing, return null. 
        }
        return oaiRecords;     
    }

    public List<OAIRecord> findOaiRecordsBySetName(String setName) {
        return findOaiRecordsBySetName(setName, null, null);
    }    
    
    public List<OAIRecord> findOaiRecordsBySetName(String setName, Date from, Date until) {
                
        String queryString ="SELECT object(h) from OAIRecord as h";
        queryString += setName != null ? " where h.setName = :setName" : ""; // where h.setName is null";
        queryString += from != null ? " and h.lastUpdateTime >= :from" : "";
        queryString += until != null ? " and h.lastUpdateTime <= :until" : "";

        logger.fine("Query: "+queryString);
        
        Query query = em.createQuery(queryString);
        if (setName != null) { query.setParameter("setName",setName); }
        if (from != null) { query.setParameter("from",from); }
        if (until != null) { query.setParameter("until",until); }
        
        try {
            return query.getResultList();      
        } catch (Exception ex) {
            logger.fine("Caught exception; returning null.");
            return null;
        }
    }
    
    // This method is to only get the records NOT marked as "deleted":
    public List<OAIRecord> findActiveOaiRecordsBySetName(String setName) {
        
        
        String queryString ="SELECT object(h) from OAIRecord as h WHERE (h.removed != true)";
        queryString += setName != null ? " and (h.setName = :setName)" : "and (h.setName is null)";
        logger.fine("Query: "+queryString);
        
        Query query = em.createQuery(queryString);
        if (setName != null) { query.setParameter("setName",setName); }
        
        try {
            return query.getResultList();      
        } catch (Exception ex) {
            logger.fine("Caught exception; returning null.");
            return null;
        }
    }
    
    // This method is to only get the records marked as "deleted":
    public List<OAIRecord> findDeletedOaiRecordsBySetName(String setName) {
        
        
        String queryString ="SELECT object(h) from OAIRecord as h WHERE (h.removed = true)";
        queryString += setName != null ? " and (h.setName = :setName)" : "and (h.setName is null)";
        logger.fine("Query: "+queryString);
        
        Query query = em.createQuery(queryString);
        if (setName != null) { query.setParameter("setName",setName); }
        
        try {
            return query.getResultList();      
        } catch (Exception ex) {
            logger.fine("Caught exception; returning null.");
            return null;
        }
    }
    
}
