package com.coremedia.blueprint.connectors.youtube;

import com.coremedia.blueprint.connectors.api.ConnectorCategory;
import com.coremedia.blueprint.connectors.api.ConnectorContext;
import com.coremedia.blueprint.connectors.api.ConnectorEntity;
import com.coremedia.blueprint.connectors.api.ConnectorException;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.connectors.api.ConnectorItem;
import com.coremedia.blueprint.connectors.api.ConnectorService;
import com.coremedia.blueprint.connectors.api.search.ConnectorSearchResult;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Playlist;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.Video;
import com.google.common.collect.Lists;
import net.sf.ehcache.CacheManager;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.CHANNEL_ID;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.CREDENTIALS_JSON;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.DISPLAY_NAME;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.USER;

public class YouTubeConnectorServiceImpl implements ConnectorService {
  private static final Logger LOGGER = LoggerFactory.getLogger(YouTubeConnectorServiceImpl.class);
  private static final String CACHE_MANAGER = "cacheManagerYouTube";
  private static final String HTTPS_WWW_GOOGLEAPIS_COM_AUTH_YOUTUBE_FORCE_SSL = "https://www.googleapis.com/auth/youtube.force-ssl";

  private YouTubeConnectorCategory rootCategory;
  private YouTubeService youTubeService;
  private YouTubeConnector youTubeConnector;

  @Resource(name = "youTubeService")
  public void setYouTubeService(YouTubeService youTubeService) {
    this.youTubeService = youTubeService;
  }


  @Override
  public boolean init(@Nonnull ConnectorContext context) throws ConnectorException {
    try {
      List<String> scopes = Lists.newArrayList(HTTPS_WWW_GOOGLEAPIS_COM_AUTH_YOUTUBE_FORCE_SSL);
      String credentialsJson = context.getProperty(CREDENTIALS_JSON);
      if (credentialsJson == null || credentialsJson.length() == 0) {
        throw new IOException("No credentialsJson found for youtube connector");
      }
      GoogleCredential credential = GoogleCredential.fromStream(new ByteArrayInputStream(credentialsJson.getBytes()));
      if (credential.createScopedRequired()) {
        credential = credential.createScoped(scopes);
      }
      YouTube youTube = new YouTube.Builder(new NetHttpTransport(), new JacksonFactory(), credential).setApplicationName("youtubeProvider").build();
      youTubeConnector = new YouTubeConnector(youTube);
      this.youTubeService.setConnector(youTubeConnector);
    } catch (IOException e) {
      throw new ConnectorException(e);
    }
    return true;
  }

  @Nullable
  @Override
  public ConnectorItem getItem(@Nonnull ConnectorContext context, @Nonnull ConnectorId id) throws ConnectorException {
    String playlistId = YouTubeIdHelper.getPlaylistId(id);
    String videoId = YouTubeIdHelper.getExternalId(id);
    Playlist playlist = getPlaylist(context, playlistId);
    Video video = youTubeService.getVideo(context.getConnectionId(), videoId);
    ConnectorId categoryId = ConnectorId.createRootId(context.getConnectionId());
    ConnectorCategory parent = rootCategory;

    //again, we have to check here if the video belongs to a playlist or not
    if (playlist != null) {
      categoryId = ConnectorId.createCategoryId(context.getConnectionId(), YouTubeIdHelper.createPlaylistId(playlist.getId()));
      parent = new YouTubeConnectorCategory(getRootCategory(context), context, categoryId, playlist, playlist.getSnippet().getTitle());
    }
    YouTubeConnectorCategory category = new YouTubeConnectorCategory(parent, context, categoryId, playlist, null);
    return new YouTubeConnectorVideo(this, category, context, id, video);
  }

  @Nullable
  @Override
  public ConnectorCategory getCategory(@Nonnull ConnectorContext context, @Nonnull ConnectorId id) throws ConnectorException {
    if (id.isRootId()) {
      return rootCategory;
    }

    String playlistId = YouTubeIdHelper.getPlaylistId(id);
    Playlist playlist = getPlaylist(context, playlistId);
    String name = playlist.getSnippet().getTitle();
    YouTubeConnectorCategory category = new YouTubeConnectorCategory(getRootCategory(context), context, id, playlist, name);
    category.setItems(getItems(context, category));
    return category;
  }

  @Nonnull
  @Override
  public ConnectorCategory getRootCategory(@Nonnull ConnectorContext context) {
//    if (rootCategory == null) {
    String displayName = context.getProperty(DISPLAY_NAME);
    ConnectorId rootId = ConnectorId.createRootId(context.getConnectionId());
    rootCategory = new YouTubeConnectorCategory(null, context, rootId, null, displayName);
    rootCategory.setSubCategories(getSubCategories(context));
    rootCategory.setItems(getItems(context, rootCategory));
//    }
    return rootCategory;
  }

  @Nonnull
  @Override
  public ConnectorSearchResult<ConnectorEntity> search(@Nonnull ConnectorContext context, ConnectorCategory category, String query, String searchType, Map<String, String> params) {
    List<ConnectorEntity> result = new ArrayList<>();
    query = query.replaceAll("\\*", " ");

    try {
      if (category != null && !category.getConnectorId().isRootId()) {
        String playlistId = YouTubeIdHelper.getPlaylistId(category.getConnectorId());
        List<PlaylistItem> playlistItems = youTubeService.getPlaylistItems(context.getConnectionId(), playlistId);
        for (PlaylistItem video : playlistItems) {
          String name = video.getSnippet().getTitle();
          if (name.toLowerCase().contains(query.toLowerCase()) || query.trim().length() == 0) {
            String videoId = video.getSnippet().getResourceId().getVideoId();
            ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), YouTubeIdHelper.createVideoId(playlistId, videoId));
            result.add(getItem(context, itemId));
          }
        }
      }
      else {
        String channelId = context.getProperty(CHANNEL_ID);
        SearchListResponse response = search(context.getConnectionId(), channelId, query);
        List<com.google.api.services.youtube.model.SearchResult> searchResults = response.getItems();
        if (searchResults != null) {
          for (com.google.api.services.youtube.model.SearchResult searchResult : searchResults) {
            String videoId = searchResult.getId().getVideoId();
            ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), YouTubeIdHelper.createVideoId("", videoId));
            result.add(getItem(context, itemId));
          }
        }
      }
    } catch (Exception e) {
      LOGGER.warn("Error retrieving videos", e.getMessage(), e);
    }
    return new ConnectorSearchResult<>(result);
  }

  @Override
  public Boolean refresh(@Nonnull ConnectorContext context, @Nonnull ConnectorCategory category) {
    CacheManager cacheManager = CacheManager.getCacheManager(CACHE_MANAGER);
    cacheManager.getCache("youTubePlayListByUserCache").removeAll();
    cacheManager.getCache("youTubePlayListByChannelCache").removeAll();
    cacheManager.getCache("youTubeVideoCache").removeAll();
    cacheManager.getCache("youTubeVideosCache").removeAll();
    cacheManager.getCache("youTubePlaylistItemsCache").removeAll();
    cacheManager.getCache("youTubeSearchCache").removeAll();
    init(context);
    return true;
  }

  @Override
  public ConnectorItem upload(@Nonnull ConnectorContext context, ConnectorCategory category, String itemName, InputStream inputStream) {
    return null;
  }

  // -------------------- Helper ---------------------------------------------------------------------------------------
  private SearchListResponse search(String connectionId, String channelId, String query) {
    try {
      return youTubeConnector.getSearchListResponse(channelId, query);
    } catch (IOException e) {
      LOGGER.error("Failed to search youtube: " + e.getMessage(), e);
    }
    return null;
  }

  private Playlist getPlaylist(ConnectorContext context, String playlistId) {
    if (StringUtils.isEmpty(playlistId)) {
      return null;
    }
    List<Playlist> playlists = getPlaylists(context);
    for (Playlist playlist : playlists) {
      if (playlist.getId().equals(playlistId)) {
        return playlist;
      }
    }
    return null;
  }

  private List<Playlist> getPlaylists(ConnectorContext context) {
    String channelId = context.getProperty(CHANNEL_ID);
    if (!StringUtils.isEmpty(channelId)) {
      return youTubeService.getPlaylistsByChannel(context.getConnectionId(), channelId);
    }

    String user = context.getProperty(USER);
    if (!StringUtils.isEmpty(user)) {
      return youTubeService.getPlaylistsByUser(context.getConnectionId(), user);
    }

    return Collections.emptyList();
  }

  /**
   * There are only one kind of subcategories which are the playlists that are children
   * of the root channel, so we don't have to care about the tree relation here
   */
  private List<ConnectorCategory> getSubCategories(@Nonnull ConnectorContext context) {
    List<ConnectorCategory> result = new ArrayList<>();
    List<Playlist> playlists = getPlaylists(context);

    for (Playlist list : playlists) {
      ConnectorId categoryId = ConnectorId.createCategoryId(context.getConnectionId(), YouTubeIdHelper.createPlaylistId(list.getId()));
      String name = list.getSnippet().getTitle();
      YouTubeConnectorCategory channel = new YouTubeConnectorCategory(rootCategory, context, categoryId, list, name);
//      channel.setItems(getItems(context, channel));
      result.add(channel);
    }
    return result;
  }

  /**
   * Helper to find the items for the given category
   */
  private List<ConnectorItem> getItems(@Nonnull ConnectorContext context, ConnectorCategory category) {
    List<ConnectorItem> result = new ArrayList<>();

    String playlistId = YouTubeIdHelper.getPlaylistId(category.getConnectorId());

    if (category.getConnectorId().isRootId()) {
      String channelId = context.getProperty(CHANNEL_ID);
      if (!StringUtils.isEmpty(channelId)) {
        List<Video> videos = youTubeService.getVideos(context.getConnectionId(), channelId);
        for (Video video : videos) {
          String vId = YouTubeIdHelper.createVideoId(playlistId, video.getId());
          ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), vId);
          result.add(getItem(context, itemId));
        }
      }
    }
    else {
      List<PlaylistItem> playlistItems = youTubeService.getPlaylistItems(context.getConnectionId(), playlistId);
      for (PlaylistItem playlistItem : playlistItems) {
        String vId = YouTubeIdHelper.createVideoId(playlistId, playlistItem.getSnippet().getResourceId().getVideoId());
        ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), vId);
        result.add(getItem(context, itemId));
      }
    }
    return result;
  }
}