package gr.iti.mklab.focused.crawler.bolts.webpages;

import static org.apache.storm.utils.Utils.tuple;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.InputSource;

import de.l3s.boilerpipe.BoilerpipeExtractor;
import de.l3s.boilerpipe.BoilerpipeProcessingException;
import de.l3s.boilerpipe.document.Image;
import de.l3s.boilerpipe.document.TextDocument;
import de.l3s.boilerpipe.document.TextDocumentStatistics;
import de.l3s.boilerpipe.estimators.SimpleEstimator;
import de.l3s.boilerpipe.extractors.CommonExtractors;
import de.l3s.boilerpipe.sax.BoilerpipeSAXInput;
import de.l3s.boilerpipe.sax.ImageExtractor;
import gr.iti.mklab.framework.common.domain.Article;
import gr.iti.mklab.framework.common.domain.MediaItem;
import gr.iti.mklab.framework.common.domain.WebPage;

public class ArticleExtractionBolt extends BaseRichBolt {

	private static final long serialVersionUID = -2548434425109192911L;
	
	public static String MEDIA_STREAM = "mediaitems";
	public static String WEBPAGE_STREAM = "webpages";
	
	private Logger _logger;
	
	private OutputCollector _collector;
	
	private BoilerpipeExtractor _extractor, _articleExtractor;
	private ImageExtractor _imageExtractor;
	private SimpleEstimator _estimator;
	
	private int minDim = 200;
	private int minArea = 200 * 200;
	private int maxUrlLength = 500;
	
	private BlockingQueue<Tuple> _queue;
	
	public ArticleExtractionBolt() {

	}
	
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
    	declarer.declareStream(MEDIA_STREAM, new Fields("mediaitems"));
    	declarer.declareStream(WEBPAGE_STREAM, new Fields("webpages"));
    }

	public void prepare(@SuppressWarnings("rawtypes") Map conf, TopologyContext context, 
			OutputCollector collector) {
		
		_logger = Logger.getLogger(ArticleExtractionBolt.class);
		
		_collector = collector;
		
		_queue = new LinkedBlockingQueue<Tuple>();

		_articleExtractor = CommonExtractors.ARTICLE_EXTRACTOR;
	    _extractor = CommonExtractors.ARTICLE_EXTRACTOR;
	    // The use of Canola extractor increases recall of the returned media items but decreases precision.
	    //_extractor = CommonExtractors.CANOLA_EXTRACTOR;
	    
	    _imageExtractor = ImageExtractor.INSTANCE;
	    
	    // Quality estimator of the article extraction process
	    _estimator = SimpleEstimator.INSTANCE;	
	    
	    Thread extractorThread = new Thread(new ArticleExtractor(_queue));
	    extractorThread.start();
	    
	}

	public void execute(Tuple input) {
		try {
			_queue.put(input);
			
		} catch (InterruptedException e) {
			_logger.error(e);
		}
	}   
	
	private class ArticleExtractor implements Runnable {

		private BlockingQueue<Tuple> queue;
		
		public ArticleExtractor(BlockingQueue<Tuple> _queue) {
			this.queue = _queue;
		}
		
		public void run() {
			while(true) {
				Tuple input = null;
				try {
					input  = queue.take();
					if(input == null) {
						continue;
					}
					
					WebPage webPage = (WebPage) input.getValueByField("webpages");
					byte[] content = input.getBinaryByField("content");
					
					if(webPage == null || content == null) {
						_collector.fail(input);
						continue;
					}
					
					List<MediaItem> mediaItems = new ArrayList<MediaItem>();
					boolean parsed = parseWebPage(webPage, content, mediaItems);
					if(parsed) { 
						_collector.emit(WEBPAGE_STREAM, input, tuple(webPage));
						for(MediaItem mediaItem : mediaItems) {
							_collector.emit(MEDIA_STREAM, tuple(mediaItem));
						}
						_collector.ack(input);
					}
					else {
						_collector.fail(input);
					}
					
				} catch (Exception e) {
					_logger.error(e);
					continue;
				}
			}
		}
	}
	
	public boolean parseWebPage(WebPage webPage, byte[] content, List<MediaItem> mediaItems) {  
	  	try { 
	  		String base = webPage.getExpandedUrl();
	  		
	  		InputSource articelIS1 = new InputSource(new ByteArrayInputStream(content));
	  		InputSource articelIS2 = new InputSource(new ByteArrayInputStream(content));
	  		
		  	TextDocument document = null, imgDoc = null;

	  		document = new BoilerpipeSAXInput(articelIS1).getTextDocument();
	  		imgDoc = new BoilerpipeSAXInput(articelIS2).getTextDocument();
	  		
	  		TextDocumentStatistics dsBefore = new TextDocumentStatistics(document, false);
	  		synchronized(_articleExtractor) {
	  			_articleExtractor.process(document);
	  		}
	  		synchronized(_extractor) {
	  			_extractor.process(imgDoc);
	  		}
	  		TextDocumentStatistics dsAfter = new TextDocumentStatistics(document, false);
	  		
	  		boolean isLowQuality = true;
	  		synchronized(_estimator) {
	  			isLowQuality = _estimator.isLowQuality(dsBefore, dsAfter);
	  		}
	  	
	  		String title = document.getTitle();
	  		
	  		if(title == null) {
	  			return false;
	  		}
	  		
	  		String text = document.getText(true, false);

	  		webPage.setTitle(title);
	  		webPage.setText(text);
	  		webPage.setArticle(!isLowQuality);
	  		
	  		mediaItems.addAll(extractArticleImages(imgDoc, webPage, base, content));		
	  		webPage.setMedia(mediaItems.size());
	  		
	  		List<String> mediaIds = new ArrayList<String>();
	  		for(MediaItem mediaItem : mediaItems) {
	  			mediaIds.add(mediaItem.getId());
	  		}
	  		webPage.setMediaIds(mediaIds.toArray(new String[mediaIds.size()]));
	  		
	  		if(mediaItems.size() > 0) {
	  			MediaItem mediaItem = mediaItems.get(0);
	  			webPage.setMediaThumbnail(mediaItem.getUrl());
	  		}
	  		
			return true;
			
	  	} catch(Exception ex) {
	  		_logger.error(ex);
	  		return false;
	  	}
	}

	public Article getArticle(WebPage webPage, byte[] content) {  
		
		String base = webPage.getExpandedUrl();
		
	  	try { 
	  		InputSource articelIS1 = new InputSource(new ByteArrayInputStream(content));
	  		InputSource articelIS2 = new InputSource(new ByteArrayInputStream(content));
		  	TextDocument document = null, imgDoc = null;

	  		document = new BoilerpipeSAXInput(articelIS1).getTextDocument();
	  		imgDoc = new BoilerpipeSAXInput(articelIS2).getTextDocument();
	  		
	  		TextDocumentStatistics dsBefore = new TextDocumentStatistics(document, false);
	  		synchronized(_articleExtractor) {
	  			_articleExtractor.process(document);
	  		}
	  		synchronized(_extractor) {
	  			_extractor.process(imgDoc);
	  		}
	  		TextDocumentStatistics dsAfter = new TextDocumentStatistics(document, false);
	  		
	  		boolean isLowQuality = true;
	  		synchronized(_estimator) {
	  			isLowQuality = _estimator.isLowQuality(dsBefore, dsAfter);
	  		}
	  	
	  		String title = document.getTitle();
	  		String text = document.getText(true, false);
	  		
	  		Article article = new Article(title, text);
	  		article.setLowQuality(isLowQuality);
	  		
	  		List<MediaItem> mediaItems = extractArticleImages(imgDoc, webPage, base, content);		
	  		//List<MediaItem> mediaItems = extractAllImages(base, title, webPage, pageHash, content);
	  		
	  		for(MediaItem mItem : mediaItems) {
	  			article.addMediaItem(mItem);
	  		}
			return article;
			
	  	} catch(Exception ex) {
	  		_logger.error(ex);
	  		return null;
	  	}
	}
	
	public List<MediaItem> extractArticleImages(TextDocument document, WebPage webPage, String base, byte[] content) 
			throws IOException, BoilerpipeProcessingException {
		
		List<MediaItem> mediaItems = new ArrayList<MediaItem>();
		
		InputSource imageslIS = new InputSource(new ByteArrayInputStream(content));
  		
  		List<Image> detectedImages;
  		synchronized(_imageExtractor) {
  			detectedImages = _imageExtractor.process(document, imageslIS);
  		}
  		
  		for(Image image  : detectedImages) {
  			Integer w = -1, h = -1;
  			try {
  				String width = image.getWidth().replaceAll("%", "");
  				String height = image.getHeight().replaceAll("%", "");
  	
  				w = Integer.parseInt(width);
  				h = Integer.parseInt(height);
  			}
  			catch(Exception e) {
  				// filter images without size
  				continue;
  			}
  			
  			// filter small images
  			if(image.getArea() < minArea || w < minDim  || h < minDim) 
				continue;

			String src = image.getSrc();
			URL url = null;
			try {
				url = new URL(new URL(base), src);
				
				if(url.toString().length() > maxUrlLength)
					continue;
				
				if(src.endsWith(".gif") || url.getPath().endsWith(".gif"))
					continue;
				
			} catch (Exception e) {
				_logger.error("Error for " + src + " in " + base);
				continue;
			}
			
			String alt = image.getAlt();
			if(alt == null) {
				alt = webPage.getTitle();
				if(alt == null)
					continue;
			}
			
			MediaItem mediaItem = new MediaItem(url);
			
			// Create image unique id. Is this a good practice? 
			int imageHash = (url.hashCode() & 0x7FFFFFFF);
			
			mediaItem.setId("Web#" + imageHash);
			mediaItem.setSource("Web");
			mediaItem.setType("image");
			mediaItem.setThumbnail(url.toString());
			
			mediaItem.setPageUrl(base.toString());
			mediaItem.setReference(webPage.getReference());
			
			mediaItem.setShares((long)webPage.getShares());
			
			mediaItem.setTitle(alt.trim());
			mediaItem.setDescription(webPage.getTitle());
			
			if(w != -1 && h != -1) 
				mediaItem.setSize(w, h);
			
			if(webPage.getDate() != null)
				mediaItem.setPublicationTime(webPage.getDate().getTime());
			
			mediaItems.add(mediaItem);
		}
  		return mediaItems;
	}
	
	
	public List<MediaItem> extractAllImages(String baseUri, String title, WebPage webPage, byte[] content) throws IOException {
		List<MediaItem> images = new ArrayList<MediaItem>();
		
		String html = IOUtils.toString(new ByteArrayInputStream(content));
		Document doc = Jsoup.parse(html, baseUri);
		
		Elements elements = doc.getElementsByTag("img");
		for(Element img  : elements) {
  			
			String src = img.attr("src");
			String alt = img.attr("alt");
			String width = img.attr("width");
			String height = img.attr("height");
			
  			Integer w = -1, h = -1;
  			try {
  				if(width==null || height==null || width.equals("") || height.equals(""))
  					continue;
  				
  				w = Integer.parseInt(width);
  				h = Integer.parseInt(height);
  				
  				// filter small images
  	  			if( (w*h) < minArea || w < minDim  || h < minDim) 
  					continue;
  			}
  			catch(Exception e) {
  				_logger.error(e);
  			}

			URL url = null;
			try {
				url = new URL(src);
				
				if(url.toString().length() > maxUrlLength)
					continue;
				
				if(src.endsWith(".gif") || url.getPath().endsWith(".gif"))
					continue;
				
			} catch (Exception e) {
				_logger.error(e);
				continue;
			}
			
			if(alt == null) {
				alt = title;
			}
			
			MediaItem mediaItem = new MediaItem(url);
			
			// Create image unique id
			String urlStr = url.toString().trim();
			int imageHash = (urlStr.hashCode() & 0x7FFFFFFF);
			
			mediaItem.setId("Web#" + imageHash);
			mediaItem.setSource("Web");
			mediaItem.setType("image");
			mediaItem.setThumbnail(url.toString());
			
			mediaItem.setPageUrl(baseUri);
			
			mediaItem.setShares((long)webPage.getShares());
			mediaItem.setTitle(alt.trim());
			mediaItem.setDescription(webPage.getTitle());
			
			if(w != -1 && h != -1) 
				mediaItem.setSize(w, h);
			
			if(webPage.getDate() != null)
				mediaItem.setPublicationTime(webPage.getDate().getTime());
			
			images.add(mediaItem);
		}
		return images;
	}
	
	public List<MediaItem> extractVideos(WebPage webPage, byte[] content) {

		String base = webPage.getExpandedUrl();
		
		List<MediaItem> videos = new ArrayList<MediaItem>(); 
		int pageHash = (base.hashCode() & 0x7FFFFFFF);
		try {
			Document doc = Jsoup.parse(new ByteArrayInputStream(content), "UTF-8", base);
			
			Elements objects = doc.getElementsByTag("object");
			
			System.out.println(objects.size()+" objects");
			
			for(Element object :objects) {
				System.out.println(object);
				String data = object.attr("data");
				if(data == null || data.equals("")) {
					System.out.println("data is null");
					continue;
				}
				try {
					URL url = new URL(data);
					MediaItem mediaItem = new MediaItem(url);
					
					int imageHash = (url.hashCode() & 0x7FFFFFFF);
					mediaItem.setId("Web#"+pageHash+"_"+imageHash);
					mediaItem.setSource("Web");
					mediaItem.setType("video");
					mediaItem.setThumbnail(url.toString());
					
					mediaItem.setPageUrl(base.toString());
					
					mediaItem.setShares((long) webPage.getShares());
				}
				catch(Exception e) {
					e.printStackTrace();
					continue;
				}
			}
		} catch (Exception e) {
			_logger.error(e);
		}
		
		return videos;
	}
}
