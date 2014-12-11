package gr.iti.mklab.focused.crawler.bolts.media;

import gr.iti.mklab.framework.client.dao.MediaItemDAO;
import gr.iti.mklab.framework.client.dao.StreamUserDAO;
import gr.iti.mklab.framework.client.dao.impl.MediaItemDAOImpl;
import gr.iti.mklab.framework.client.dao.impl.StreamUserDAOImpl;
import gr.iti.mklab.framework.client.mongo.UpdateItem;
import gr.iti.mklab.framework.common.domain.Concept;
import gr.iti.mklab.framework.common.domain.MediaItem;
import gr.iti.mklab.framework.common.domain.StreamUser;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;

public class MediaUpdaterBolt extends BaseRichBolt {

    /**
	 * 
	 */
	private static final long serialVersionUID = -2548434425109192911L;
	
	private Logger _logger;
	
	private String _mongodbHostname;
	private String _mediaItemsDB;
	private String _mediaItemsCollection;
	private String _streamUsersDB;
	private String _streamUsersCollection;
	
	private MediaItemDAO _mediaItemDAO;
	private StreamUserDAO _streamUsersDAO;
	//private OutputCollector _collector;

	private long received = 0;
	private long newMedia=0, existedMedia = 0;
	
	public MediaUpdaterBolt(String mongodbHostname, String mediaItemsDB, String mediaItemsCollection, 
			String streamUsersDB, String streamUsersCollection) {
		
		_mongodbHostname = mongodbHostname;
		_mediaItemsDB = mediaItemsDB;
		_mediaItemsCollection = mediaItemsCollection;
		
		_streamUsersDB = streamUsersDB;
		_streamUsersCollection = streamUsersCollection;
	}
	
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
    	declarer.declare(new Fields("MediaItem"));
    }

	public void prepare(@SuppressWarnings("rawtypes") Map conf, TopologyContext context, 
			OutputCollector collector) {
		
		_logger = Logger.getLogger(MediaUpdaterBolt.class);
		try {
			_mediaItemDAO = new MediaItemDAOImpl(_mongodbHostname, _mediaItemsDB, _mediaItemsCollection);
			_streamUsersDAO = new StreamUserDAOImpl(_mongodbHostname, _streamUsersDB, _streamUsersCollection);
			//_collector = collector;
		} catch (Exception e) {
			_logger.error(e);
		}
		
	}

	public void execute(Tuple tuple) {
		if(_mediaItemDAO != null) {
			try {
				
			if(++received%1000==0) {
				_logger.info(received + " media items received. " + newMedia + " are new and " + existedMedia + " already exists!");
			}
				
			MediaItem mediaItem = (MediaItem) tuple.getValueByField("MediaItem");
				if(mediaItem == null)
					return;
			
				String mId = mediaItem.getId();
				
				if(_mediaItemDAO.exists(mId)) {
				
					existedMedia++;
					
					UpdateItem update = new UpdateItem();
					update.setField("vIndexed", mediaItem.isVisualIndexed());
					update.setField("status", mediaItem.isVisualIndexed() ? "indexed" : "failed");
				
					Integer width = mediaItem.getWidth();
					Integer height = mediaItem.getHeight();
					if(width != null && height != null && width != -1 && height != -1) {
						update.setField("height", height);
						update.setField("width", width);
					}
				
					List<Concept> concepts = mediaItem.getConcepts();
					if(concepts != null) {
						update.setField("concepts", concepts);
					}
					
					String clusterId = mediaItem.getClusterId();
					if(clusterId != null) {
						update.setField("clusterId", clusterId);
					}
					
					_mediaItemDAO.updateMediaItem(mediaItem.getId(), update);
				}
				else {
					newMedia++;
					_mediaItemDAO.addMediaItem(mediaItem);
					
					StreamUser user = mediaItem.getUser();
					
					if(user != null && _streamUsersDAO != null) {
						user.setLastUpdated(System.currentTimeMillis());
						
						if(!_streamUsersDAO.exists(user.getId())) {
							_streamUsersDAO.insertStreamUser(user);
						}
					}
				}
				
			}
			catch(Exception e) {
				_logger.error(e);
			}
		}
	}   
	
}