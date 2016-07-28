mklab-focused-crawler
=====================

The main purpose of mklab-focused-crawler is fetching, parsing, analysis and indexing of web pages shared through social networks. Also this module collects multimedia content
embedded in webpages or shared in social media platforms and index it for future use.

The main pipeline of focused crawler is implemented as a storm topology, where the sequantial bolts perform a specific operation on the crawling procedure. The overall topology of focused crawler is depiced in the following figure.
![focused crawler topology](https://github.com/MKLab-ITI/mklab-focused-crawler/blob/dice/imgs/storm%20topologies.png)

The input stream consists of URLs: these refer either to arbitrary web pages or to social media pages. There are three spouts that are possible to inject URLs in the topology: a) one that periodically reads URLs from a running mongo database instance, b) one that listens to a Redis message broker following the Publish/Subscribe pattern, and c) one waiting for URLs from a Kafka queue.
The URLs fed to the crawler may be produced by any independent process. One possibility is to use the [Stream Manager project](https://github.com/MKLab-ITI/mklab-stream-manager).

The web pages injected in the topology have the following json structure:
```sh
  {
      "_id": "https://youtu.be/zvad7iztAIM",
      "url": "https://youtu.be/zvad7iztAIM",
      "date": ISODate("2016-07-11T10:37:17.0Z"),
      "reference": "Twitter#752451689530089473",
      "source": "Twitter"
  }
```

There is a url (usually shortened), a publication date, the id of the social media item contain that url, and an identifier of the social media platform. While web page objects pass through the topology, they are updated with additional fields.

The first bolt in the topology deserializes the messages injected in the topology by the spouts, from a json object to [WebPage](https://github.com/MKLab-ITI/mklab-framework-common/blob/master/src/main/java/gr/iti/mklab/framework/common/domain/WebPage.java) objects. As URLs on Twitter are usually shortened, the next bolt ([URLExpansionBolt](https://github.com/MKLab-ITI/mklab-focused-crawler/blob/dice/src/main/java/gr/iti/mklab/focused/crawler/bolts/webpages/URLExpansionBolt.java)) expands them to long form. The next bolt checks the type of the URLs and its crawling status. URLs that correspond to posts in popular social media platforms (e.g., https://www.youtube.com/watch?v=LHAZYK6x6iE) are redirected to a bolt named [MediaExtractionBolt](https://github.com/MKLab-ITI/mklab-focused-crawler/blob/dice/src/main/java/gr/iti/mklab/focused/crawler/bolts/media/MediaExtractionBolt.java), which retrieves metadata from the respective platforms.

URLs to arbitrary web pages are emitted to a [Fetcher bolt](https://github.com/MKLab-ITI/mklab-focused-crawler/blob/dice/src/main/java/gr/iti/mklab/focused/crawler/bolts/webpages/WebPageFetcherBolt.java). Non-HTML content is discarded. The fetched content is then forwarded to the next bolt ([ArticleExtractionBolt](https://github.com/MKLab-ITI/mklab-focused-crawler/blob/dice/src/main/java/gr/iti/mklab/focused/crawler/bolts/webpages/ArticleExtractionBolt.java)) that attempts to extract articles and embedded media items. The extracted articles are indexed in a running Solr instance by the Text Indexer. The extracted media items, as well as the media items coming from the MediaExtractionBolt are handled by the Media Text Indexer.


### Building & Configuration  

To build the executable jar use the following mvn command:
```sh
  $mvn clean assembly:assembly
```

The main class of the topology is *gr.iti.mklab.focused.crawler.DICECrawler*. This entry point is specified in the pom.xml file in the maven-assembly-plugin.
