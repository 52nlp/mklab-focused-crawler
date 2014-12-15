package gr.iti.mklab.focused.crawler.bolts.streams;

import gr.iti.mklab.framework.abstractions.socialmedia.items.TwitterItem;
import gr.iti.mklab.framework.common.domain.Account;
import gr.iti.mklab.framework.common.domain.Item;
import gr.iti.mklab.framework.common.domain.feeds.AccountFeed;
import gr.iti.mklab.framework.common.domain.feeds.Feed;
import gr.iti.mklab.framework.common.domain.feeds.LocationFeed;
import gr.iti.mklab.framework.common.domain.feeds.KeywordsFeed;
import gr.iti.mklab.framework.common.domain.feeds.Feed.FeedType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import twitter4j.FilterQuery;
import twitter4j.ResponseList;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.User;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

public class TwitterStreamBolt extends BaseRichBolt {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6470201177858275997L;

	private Logger _logger;

	private OutputCollector _collector;

	private TwitterStream _twitterStream;
	private Twitter _twitterApi;

	String oAuthConsumerKey = null;
	String oAuthConsumerSecret = null;
	String oAuthAccessToken = null;
	String oAuthAccessTokenSecret = null;
	
	public TwitterStreamBolt(String oAuthConsumerKey, String oAuthConsumerSecret, String oAuthAccessToken, 
			String oAuthAccessTokenSecret) {
		this.oAuthConsumerKey = oAuthConsumerKey;
		this.oAuthConsumerSecret = oAuthConsumerSecret;
		this.oAuthAccessToken = oAuthAccessToken;
		this.oAuthAccessTokenSecret = oAuthAccessTokenSecret;
	}
	
	public void prepare(@SuppressWarnings("rawtypes") Map stormConf, TopologyContext context,
			OutputCollector collector) {
		
		_logger = Logger.getLogger(TwitterStreamBolt.class);
		_collector = collector;

		if (oAuthConsumerKey == null || oAuthConsumerSecret == null ||
				oAuthAccessToken == null || oAuthAccessTokenSecret == null) {
			_logger.error("#Twitter : Stream requires authentication");
		}
		
		_logger.info("Twitter Credentials: \n" + 
						"oAuthConsumerKey:  " + oAuthConsumerKey  + "\n" +
						"oAuthConsumerSecret:  " + oAuthConsumerSecret  + "\n" +
						"oAuthAccessToken:  " + oAuthAccessToken + "\n" +
						"oAuthAccessTokenSecret:  " + oAuthAccessTokenSecret);
		
		ConfigurationBuilder cb = new ConfigurationBuilder();
		cb.setJSONStoreEnabled(true)
			.setOAuthConsumerKey(oAuthConsumerKey)
			.setOAuthConsumerSecret(oAuthConsumerSecret)
			.setOAuthAccessToken(oAuthAccessToken)
			.setOAuthAccessTokenSecret(oAuthAccessTokenSecret);
		Configuration conf = cb.build();
		
		StatusListener listener = getListener();
		_twitterStream = new TwitterStreamFactory(conf).getInstance();	
		_twitterStream.addListener(listener);
		
		_twitterApi = new TwitterFactory(conf).getInstance();
	}

	public void execute(Tuple input) {
		try {
			@SuppressWarnings("unchecked")
			List<Feed> feeds = (List<Feed>) input.getValueByField("feeds");
			_logger.info(feeds.size() + " feeds.");
			
			subscribe(feeds);
		}
		catch(Exception e) {
			_logger.error("Exception on subscribe.", e);
		}
	}

	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declare(new Fields("Item"));
	}

	public synchronized void subscribe(List<Feed> feeds) {	
			
		List<String> keys = new ArrayList<String>();
		List<String> users = new ArrayList<String>();
		List<Long> userids = new ArrayList<Long>();
		List<double[]> locs = new ArrayList<double[]>();
			
		for(Feed feed : feeds) {
			if(feed.getFeedtype().equals(FeedType.KEYWORDS)) {
		
				KeywordsFeed keywordFeed = (KeywordsFeed) feed;
				List<String> keywords = keywordFeed.getKeywords();
				keys.addAll(keywords);
			}
			else if(feed.getFeedtype().equals(FeedType.ACCOUNT)) {
				Account source = ((AccountFeed) feed).getAccount();		
				if(source.getId() == null) {
					try {
						users.add(source.getName());
					}
					catch(Exception e) {
						continue;
					}
				}
				else {
					userids.add(Long.parseLong(source.getId()));
				}
			}
			else if(feed.getFeedtype().equals(FeedType.LOCATION)) {
				double[] location = new double[2];
					
				location[0] = ((LocationFeed) feed).getLocation().getLatitude();
				location[1] = ((LocationFeed) feed).getLocation().getLongitude();
				locs.add(location);
			}
		}
			
		Set<Long> temp = getUserIds(users);
		userids.addAll(temp);
			
		String[] keywords = new String[keys.size()];
		long[] follows = new long[userids.size()];
		double[][] locations = new double[locs.size()][2];
			
		for(int i=0;i<keys.size();i++)
			keywords[i] = keys.get(i);
			
		for(int i=0;i<userids.size();i++)
			follows[i] = userids.get(i);
			
		for(int i=0;i<locs.size();i++)
			locations[i] = locs.get(i);

		FilterQuery fq = getFilterQuery(keywords, follows, locations);
		if (fq != null) {
			_logger.info("Start tracking from twitter stream");
			_twitterStream.shutdown();
			_twitterStream.filter(fq);
		}
		else {
			_logger.info("Start sampling from twitter stream");
			_twitterStream.sample();
		}
	}
		
	private StatusListener getListener() { 
		return new StatusListener() {
			public void onStatus(Status status) {
				if(status != null) {
					try {
						// Update original tweet in case of retweets
						Status rtStatus = status.getRetweetedStatus();
						if(rtStatus != null) {
							Item rtItem = new TwitterItem(rtStatus);
							_collector.emit(new Values(rtItem));
						}
						
						// store
						Item item = new TwitterItem(status);
						_collector.emit(new Values(item));
					}
					catch(Exception e) {
						_logger.error("Exception onStatus: ", e);
					}
				}
			}
			
			public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
				try {
					String id = Long.toString(statusDeletionNotice.getStatusId());
					
					Item item = new Item();
					item.setId(id);
					item.setSource("Twitter");
					
					_collector.emit(new Values(item));
				}
				catch(Exception e) {
					_logger.error("Exception onDeletionNotice: ", e);
				}
			}
			
			public void onTrackLimitationNotice(int numOfLimitedStatuses) {
				_logger.error("Rate limit: " + numOfLimitedStatuses);
			}
			
			public void onException(Exception ex) {
				_logger.error("Internal stream error occured: " + ex.getMessage());
			}
			
			public void onScrubGeo(long userid, long arg1) {
				_logger.info("Remove appropriate geolocation information for user " + userid + " up to tweet with id " + arg1);
			}
			
			public void onStallWarning(StallWarning warn) {	
				if(warn != null) {
					_logger.error("Stall Warning " + warn.getMessage() + "(" + warn.getPercentFull() + ")");
				}
			}
		};
	}
	
	private FilterQuery getFilterQuery(String[] keywords, long[] follows, double[][] locations) {
		FilterQuery query = new FilterQuery();
		boolean empty = true;
		if (keywords != null && keywords.length > 0) {
			query = query.track(keywords);
			empty = false;
		}
		
		if (follows != null && follows.length > 0) {
			query = query.follow(follows);
			empty = false;
		}
		
		if (locations != null && locations.length > 0) {
			query = query.locations(locations);
			empty = false;
		}
		
		if (empty) 
			return null;
		else 
			return query;
	}
	
	private Set<Long> getUserIds(List<String> followsUsernames) {
		
		Set<Long> ids = new HashSet<Long>();
		List<String> usernames = new ArrayList<String>(followsUsernames.size());
		for(String username : followsUsernames) {
			usernames.add(username);
		}
		
		int size = usernames.size();
		int start = 0, end = Math.min(start+100, size);
		while(start < size) {
			List<String> sublist = usernames.subList(start, end);
			String[] _usernames = sublist.toArray(new String[sublist.size()]);
			try {
				ResponseList<User> users = _twitterApi.lookupUsers(_usernames);
				_logger.info("Request for " + _usernames.length + " users. Got " + users.size());
				for(User user : users) {
					long id = user.getId();
					ids.add(id);
				}
			} catch (TwitterException e) {
				_logger.error("Error while getting user ids from twitter...");
				_logger.error("Exception in getUserIds: ", e);
				break;
			}
			start = end + 1;
			end = Math.min(start+100, size);
		}
		return ids;
	}
}
