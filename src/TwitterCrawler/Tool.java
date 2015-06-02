package TwitterCrawler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;

import twitter4j.IDs;
import twitter4j.Paging;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.TwitterException;
import twitter4j.URLEntity;
import twitter4j.User;
import twitter4j.UserMentionEntity;
import Common.EgoNetwork;
import Common.Settings;
import Common.Utils;

public class Tool {
	private EgoNetwork network = null;
	private ExecutorService exeService = null;
	private final String language;
	private final String outputPath;
	private HashMap<String, Long> screenNameMap;
	
	private AppManager mAppManager = null;
	private Utils utils = new Utils();
	private String msgLog = new String();
	
	public Tool(EgoNetwork network, ExecutorService exeService) {
		mAppManager = AppManager.getSingleton();
		
		this.network = network;
		this.exeService = exeService;
		User egoUser = getUser(network.getEgoUser().id);
		if (egoUser == null) {
			System.out.println("Ego-user is invalid user.");
			System.exit(0);
		}
		this.language = egoUser.getLang();
		
		this.outputPath = Settings.PATH_SAVE + network.getEgoUser().id + "_" + network.level();
		new File(outputPath + "/friends/").mkdirs();
		new File(outputPath + "/tweets/").mkdirs();
		new File(outputPath + "/sharings/").mkdirs();
		new File(outputPath + "/reweets/").mkdirs();
		new File(outputPath + "/mentions/").mkdirs();
		new File(outputPath + "/favorites/").mkdirs();
		
		screenNameMap = new HashMap<String, Long>();
		screenNameMap.put(egoUser.getScreenName(), egoUser.getId());
	}
	
	/**
	 * Get following users IDs of a given user.
	 * @param userID ID number of a user to be crawled
	 * @param maxCount maximum number of a user's followers
	 * @return the following users IDs of a given user with the maximum length of 'maxCount'
	 */
	public ArrayList<Long> getFollowingsIDs(long userID, int maxCount) {
		String endpoint = "/friends/ids";
		ArrayList<Long> followings = new ArrayList<Long>();
		TwitterApp app = null;
		
		try {
			long cursor = -1;
			while (cursor != 0) {
				// Read friend_IDs from Twitter API per page
				app = mAppManager.getAvailableApp(endpoint);
				IDs followingsIDs = app.twitter.getFriendsIDs(userID, cursor);
				
				// Set friend list per page
				for (long followingID : followingsIDs.getIDs()) {
					followings.add(followingID);
					
					// Limit maximum number of following users to 'count'
					if (followings.size() == maxCount)
						return followings;
				}
				
				cursor = followingsIDs.getNextCursor();
			}
		} catch (TwitterException te) {
			if (te.exceededRateLimitation()) {
				// Register current application as limited one
				mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
				app.printRateLimitStatus(endpoint);
				
				// Retry
				followings = getFollowingsIDs(userID, maxCount);
			} else {
				try {
					switch (te.getStatusCode()) {
					case 401:	// Authentication credentials were missing or incorrect.
						network.getAuthInvalidList().add(userID);
						break;
					case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
						System.out.println("Error 404 on getting followings: " + userID);
						network.getAuthInvalidList().add(userID);
						break;
					case 503:	// The Twitter servers are up, but overloaded with requests.
					case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
						te.printStackTrace();
						Thread.sleep(5000);
						followings = getFollowingsIDs(userID, maxCount);
						break;
					default:
						te.printStackTrace();
						break;
					}
				} catch (InterruptedException ie) {
				}
			}
		}
		return followings;
	}
	
	/**
	 * Get followers IDs of a given user.
	 * @param userID ID number of a user to be crawled
	 * @param maxCount maximum number of a user's followers
	 * @return the followers IDs of a given user with the maximum length of 'maxCount'
	 */
	public ArrayList<Long> getFollowersIDs(long userID, int maxCount) {
		String endpoint = "/followers/ids";
		ArrayList<Long> followers = new ArrayList<Long>();
		TwitterApp app = null;
		
		try {
			long cursor = -1;
			while (cursor != 0) {
				// Read follower_IDs from Twitter API per page
				app = mAppManager.getAvailableApp(endpoint);
				IDs followersIDs = app.twitter.getFollowersIDs(userID, cursor);
				
				// Set follower list per page
				for (long followerID : followersIDs.getIDs()) {
					followers.add(followerID);
					
					// Limit maximum number of following users to 'count'
					if (followers.size() == maxCount)
						return followers;
				}
				
				cursor = followersIDs.getNextCursor();
			}
		} catch (TwitterException te) {
			if (te.exceededRateLimitation()) {
				// Register current application as limited one
				mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
				app.printRateLimitStatus(endpoint);
				
				// Retry
				followers = getFollowersIDs(userID, maxCount);
			} else {
				try {
					switch (te.getStatusCode()) {
					case 401:	// Authentication credentials were missing or incorrect.
						network.getAuthInvalidList().add(userID);
						break;
					case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
						System.out.println("Error 404 on getting followers: " + userID);
						network.getAuthInvalidList().add(userID);
						break;
					case 503:	// The Twitter servers are up, but overloaded with requests.
					case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
						te.printStackTrace();
						Thread.sleep(5000);
						followers = getFollowersIDs(userID, maxCount);
						break;
					default:
						te.printStackTrace();
						break;
					}
				} catch (InterruptedException ie) {
				}
			}
		}
		return followers;
	}
	
	/**
	 * Get friends list of a given user comparing followings list and followers list.
	 * To judge friendship between users, they should mutually follow each other.
	 * The <b>reason why finding friendship should be preceded</b> is to reduce API calling frequency.
	 * @param userID ID number of a user
	 * @return friends' IDs list
	 */
	public ArrayList<Long> getFriends(long userID) {
		ArrayList<Long> followings = getFollowingsIDs(userID, 5000);
		ArrayList<Long> followers = getFollowersIDs(userID, 5000);
		
		ArrayList<Long> candidates = new ArrayList<Long>();
		for (long follower : followers) {
			if (followings.contains(follower))
				candidates.add(follower);
		}
		
		ArrayList<Long> friends = filterOutlierUsers(candidates);
		return friends;
	}
	
	/**
	 * Filter outlier users checking the number of followers or followings.
	 * The outlier criteria is shown in the code.
	 * @param candidatesIDs
	 * @return the revised friends' IDs list
	 */
	public ArrayList<Long> filterOutlierUsers(ArrayList<Long> candidatesIDs) {
		String endpoint = "/users/lookup";
		ArrayList<Long> friends = new ArrayList<Long>();
		TwitterApp app = null;
		
		if (candidatesIDs.isEmpty())
			return friends;
		
		int cursor = 0;
		long[] buffer = null;
		while (cursor < candidatesIDs.size()) {
			// Make buffer space
			int remaining = candidatesIDs.size() - cursor;
			if (remaining >= 100)
				buffer = new long[100];
			else
				buffer = new long[remaining];
			
			// Fill buffer with IDs
			for (int i = 0; i < buffer.length; i++) {
				buffer[i] = candidatesIDs.get(cursor);
				cursor++;
			}
			
			try {
				app = mAppManager.getAvailableApp(endpoint);
				ResponseList<User> testset = app.twitter.lookupUsers(buffer);
				for (User user : testset) {
					// Filtering criteria
					if (user.getFriendsCount() > 5000 || user.getFollowersCount() > 5000 || !user.getLang().equals(language))
						continue;
					
					friends.add(user.getId());
					screenNameMap.put(user.getScreenName(), user.getId());
				}
			} catch (TwitterException te) {
				if (te.exceededRateLimitation()) {
					// Register current application as limited one
					mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
					app.printRateLimitStatus(endpoint);
					
					// Retry
					return filterOutlierUsers(candidatesIDs);
				} else {
					try {
						switch (te.getStatusCode()) {
						case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
							for (long id : buffer) {
								User user = getUser(id);
								if (user != null) {
									// Filtering criteria
									if (user.getFriendsCount() > 5000 || user.getFollowersCount() > 5000 || !user.getLang().equals(language))
										continue;
									
									friends.add(user.getId());
									screenNameMap.put(user.getScreenName(), user.getId());
								}
							}
							break;
						case 503:	// The Twitter servers are up, but overloaded with requests.
						case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
							te.printStackTrace();
							Thread.sleep(5000);
							return filterOutlierUsers(candidatesIDs);
						default:
							te.printStackTrace();
							break;
						}
					} catch (InterruptedException ie) {
					}
				}
			}
		}
		return friends;
	}
	
	/**
	 * Get User object from user ID.
	 * @param userID
	 * @return User object of a given user ID
	 */
	public User getUser(long userID) {
		String endpoint = "/users/show/:id";
		TwitterApp app = null;
		try {
			app = mAppManager.getAvailableApp(endpoint);
			User user = app.twitter.showUser(userID);
			return user;
		} catch (TwitterException te) {
			if (te.exceededRateLimitation()) {
				// Register current application as limited one
				mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
				app.printRateLimitStatus(endpoint);
				
				// Retry
				return getUser(userID);
			} else {
				try {
					switch (te.getStatusCode()) {
					case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
						System.out.println("Error 404 on getting user: " + userID);
						network.getAuthInvalidList().add(userID);
						return null;
					case 503:	// The Twitter servers are up, but overloaded with requests.
					case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
						te.printStackTrace();
						Thread.sleep(5000);
						return getUser(userID);
					default:
						te.printStackTrace();
						return null;
					}
				} catch (InterruptedException ie) {
					return null;
				}
			}
		}
	}
	
	/**
	 * Write network structure information into files.
	 * The file writer runs as a separate thread.
	 */
	public void writeFriendsList() {
		utils.writeFriendsIDs(outputPath + "/friends/", network, exeService);
	}
	
	/**
	 * Get timeline data of a given user. Note that there are pagination limits Rest API Limit.
	 * Clients may access a theoretical <b>maximum of 3,200 statuses</b> via the page
	 * and count parameters for the user_timeline REST API methods.
	 * Requests for more than the limit will result in a reply with a status code of 200
	 * and an empty result in the format requested.
	 * To ensure performance of the site, this artificial limit is temporarily in place.
	 * @param userID ID number of a user to be crawled
	 */
	public void loadTimeline(long userID) {
		String endpoint = "/statuses/user_timeline";
		ArrayList<Status> tweets = new ArrayList<Status>();
		
		// Get all tweets of a given user. A set of tweet messages includes user's sharings, mentions, and retweets.
		TwitterApp app = null;
		int page = 1;
		try {
			while (true) {
				app = mAppManager.getAvailableApp(endpoint);
				ResponseList<Status> onePage = app.twitter.getUserTimeline(userID, new Paging(page, 200));
				if (onePage.size() == 0)
					break;
				tweets.addAll(onePage);
				page++;
			}
		} catch (TwitterException te) {
			if (te.exceededRateLimitation()) {
				// Register current application as limited one
				mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
				app.printRateLimitStatus(endpoint);
				
				// Retry
				loadTimeline(userID);
				return;
			} else {
				try {
					switch (te.getStatusCode()) {
					case 401:	// Authentication credentials were missing or incorrect.
						network.getAuthInvalidList().add(userID);
						return;
					case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
						System.out.println("Error 404 on getting timelines: " + userID);
						network.getAuthInvalidList().add(userID);
						return;
					case 503:	// The Twitter servers are up, but overloaded with requests.
					case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
						te.printStackTrace();
						Thread.sleep(5000);
						loadTimeline(userID);
						return;
					default:
						te.printStackTrace();
						return;
					}
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
		
		// Extract sharings, retweets, and mentions from timeline data.
		Thread thread = new Thread() {
			@Override
			public void run() {
				super.run();
				HashMap<Long, Long> sharings = new HashMap<Long, Long>();
				ArrayList<Status> retweets = new ArrayList<Status>();
				HashMap<Long, Integer> mentions = new HashMap<Long, Integer>();
				
				for (Status tweet : tweets) {
					// Sharing tweets
					URLEntity[] urlEntities = tweet.getURLEntities();
					for (int i = 0; i < urlEntities.length; i++) {
						String expandedURL = urlEntities[i].getExpandedURL();
						if (expandedURL.startsWith("https://twitter.com/")) {
							String tokens[] = expandedURL.split("/");
							if (tokens.length < 6 && tokens[4].equals("status") == false)
								continue;
							
							Long targetUserID = screenNameMap.get(tokens[3]);
							Long targetTweetID = null;
							for (int c = 1; c <= tokens[5].length(); c++) {
								try {
									targetTweetID = Long.parseLong(tokens[5].substring(0, c));
								} catch (Exception e) {
									targetTweetID = Long.parseLong(tokens[5].substring(0, c - 1));
									break;
								}
							}
							
							if (targetUserID != null && targetTweetID != null
									&& userID != targetUserID && network.getNodeMap().containsKey(targetUserID))
								sharings.put(targetTweetID, targetUserID);
						}
					}
					
					// Retweets
					if (tweet.isRetweet() == true) {
						Long authorID = tweet.getRetweetedStatus().getUser().getId();
						if (userID != authorID && network.getNodeMap().containsKey(authorID))
							retweets.add(tweet);
					}
					
					// Mentions
					UserMentionEntity[] mentionEntities = tweet.getUserMentionEntities();
					for (int i = 0; i < mentionEntities.length; i++) {
						Long mentionedUserID = mentionEntities[i].getId();
						if (userID != mentionedUserID && network.getNodeMap().containsKey(mentionedUserID)) {
							Integer cnt = mentions.get(mentionedUserID);
							if (cnt == null)
								cnt = 0;
							cnt++;
							mentions.put(mentionEntities[i].getId(), cnt);
						}
					}
				}
				
				utils.writeTweets(outputPath + "/tweets/", userID, tweets);
				utils.writeSharingIDs(outputPath + "/sharings/", userID, sharings);
				utils.writeRetweetIDs(outputPath + "/reweets/", userID, retweets);
				utils.writeMentions(outputPath + "/mentions/", userID, mentions);
			}
		};
		exeService.submit(thread);
	}
	
	/**
	 * Get favorites data of a given user using Twitter API.
	 * If Twitter API reaches rate limited, try the method again and recursively.
	 * @param userID ID number of a user to be crawled
	 */
	public void loadFavorites(long userID) {
		String endpoint = "/favorites/list";
		ArrayList<Status> favorites = new ArrayList<Status>();
		TwitterApp app = null;
		int page = 1;
		try {
			while (true) {
				// Read favorite statuses from Twitter API per page
				app = mAppManager.getAvailableApp(endpoint);
				ResponseList<Status> onePage = app.twitter.getFavorites(userID, new Paging(page, 200));
				if (onePage.size() == 0)
					break;
				favorites.addAll(onePage);
				page++;
			}
		} catch (TwitterException te) {
			if (te.exceededRateLimitation()) {
				// Register current application as limited one
				mAppManager.registerLimitedApp(app, endpoint, te.getRateLimitStatus().getSecondsUntilReset());
				app.printRateLimitStatus(endpoint);
				
				// Retry
				loadFavorites(userID);
				return;
			} else {
				try {
					switch (te.getStatusCode()) {
					case 401:	// Authentication credentials were missing or incorrect.
						network.getAuthInvalidList().add(userID);
						return;
					case 404:	// The URI requested is invalid or the resource requested, such as a user, does not exists.
						System.out.println("Error 404 on getting favorites: " + userID);
						network.getAuthInvalidList().add(userID);
						return;
					case 503:	// The Twitter servers are up, but overloaded with requests.
					case -1:	// Caused by: java.net.UnknownHostException: api.twitter.com
						te.printStackTrace();
						Thread.sleep(5000);
						loadFavorites(userID);
						return;
					default:
						te.printStackTrace();
						return;
					}
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
		
		ArrayList<Status> finalList = new ArrayList<Status>();
		for (Status favorite : favorites) {
			Long authorID = favorite.getUser().getId();
			if (userID != authorID && network.getNodeMap().containsKey(authorID))
				finalList.add(favorite);
		}
		
		// Write as a file
		utils.writeFavorites(outputPath + "/favorites/", userID, finalList);
	}
	
	/**
	 * Print a given log message out. You are able to flush the log message to *.log file as well.
	 * @param log log message
	 * @param flush If true, the concatenated log texts are saved into a log file.
	 */
	public void printLog(String log, boolean flush) {
		System.out.println(log);
		msgLog = msgLog.concat(log + "\r\n");
		
		if (flush == true) {
			PrintWriter writer = null;
			try {
				writer = new PrintWriter(outputPath + "/" + network.getEgoUser().id + ".log", "utf-8");
				writer.print(msgLog);
			} catch (UnsupportedEncodingException uee) {
				uee.printStackTrace();
			} catch (FileNotFoundException fnfe) {
				fnfe.printStackTrace();
			} finally {
				if (writer != null)
					writer.close();
				msgLog = new String("");
			}
		}
	}
	
	/**
	 * Print currently-allocated memory
	 */
	public String getMemoryUsage() {
		return new String("### Current memory usage: " + utils.getCurMemoryUsage() + " MB");
	}
}