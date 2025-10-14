package de.Raphael.Main;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {
	private static void CreateDefaultsIfMissing()
	{
		// We init again to load anything from disk, allows us to update the disk file without having to restart the application.
		DataCache.Init();
		if (!DataCache.Has("branches"))
		{
			JSONArray branches = new JSONArray();
			branches.put("public");
			branches.put("prerelease");
			branches.put("dev");
			branches.put("x86-64");
			DataCache.Set("branches", branches);
			DataCache.Save();
		}
		
		if (!DataCache.Has("lastUpdates"))
		{
			DataCache.Set("lastUpdates", new JSONObject());
			DataCache.Save();
		}
		
		if (!DataCache.Has("apiKey"))
		{
			DataCache.Set("apiKey", "SETME");
		}
		
		if (!DataCache.Has("appID"))
		{
			DataCache.Set("appID", "4000"); // Normally can be 4000 or 4020
		}
		
		if (!DataCache.Has("callType"))
		{
			DataCache.Set("callType", "SingleBranch");
		}
	}
	
	private static final HttpClient client = HttpClient.newHttpClient();
	private static final ObjectMapper mapper = new ObjectMapper();
	public static void main(String[] args)
	{
		System.out.println("Started");
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		scheduler.scheduleAtFixedRate(() -> {
			try {
				CreateDefaultsIfMissing();
				String appID = (String)DataCache.Get("appID");
				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create("https://api.steamcmd.net/v1/info/" + appID))
						.GET()
						.build();

				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				
				JsonNode json = mapper.readTree(response.body());
				JsonNode dataNode = json.get("data").get(appID).get("depots").get("branches");
				
				JSONArray branches = (JSONArray)DataCache.Get("branches");
				JSONObject lastUpdates = (JSONObject)DataCache.Get("lastUpdates");
				if (branches != null && lastUpdates != null)
				{
					boolean hasUpdate = false;
					for (int i = 0; i < branches.length(); i++)
					{
						String branch = branches.getString(i);
						Long lastUpdate = dataNode.get(branch).get("timeupdated").asLong();
						
						if (!lastUpdates.has(branch) || lastUpdate != lastUpdates.getLong(branch))
						{
							System.out.println("New update for branch " + branch + "!");
							lastUpdates.put(branch, lastUpdate);
							
							hasUpdate = true;
							
							String callType = (String)DataCache.Get("callType");
							if (callType.equalsIgnoreCase("SingleBranch"))
							{
								// Dispatching GLuaTest workflow
								JSONObject inputs = new JSONObject();
								inputs.put("branch", branch);
								inputs.put("game_version", lastUpdate);
									
								JSONObject body = new JSONObject();
								body.put("ref", "main");
								body.put("inputs", inputs);
								
								HttpRequest githunRequest = HttpRequest.newBuilder()
										.uri(URI.create("https://api.github.com/repos/CFC-Servers/GLuaTest/actions/workflows/update_single_branch.yml/dispatches"))
										.header("Accept", "application/vnd.github+json")
										.header("Authorization", "Bearer " + (String)DataCache.Get("apiKey"))
										.header("X-GitHub-Api-Version", "2022-11-28")
										.header("Content-Type", "application/json")
										.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
										.build();
									
								HttpResponse<String> githubResponse = client.send(githunRequest, HttpResponse.BodyHandlers.ofString());
								if (githubResponse.statusCode() == 204)
								{
									System.out.println("Successfully dispatched GLuaTest workflow for " + branch + " :3");
								}
							}
						}
					}
					
					String callType = (String)DataCache.Get("callType");
					if (hasUpdate && (callType.equalsIgnoreCase("AllBranches") || callType.equalsIgnoreCase("AllBranchesForceRebuild")))
					{
						// Dispatching GLuaTest workflow
						JSONObject inputs = new JSONObject();
						inputs.put("force_rebuild", callType.equalsIgnoreCase("AllBranchesForceRebuild"));
							
						JSONObject body = new JSONObject();
						body.put("ref", "main");
						body.put("inputs", inputs);
						
						HttpRequest githunRequest = HttpRequest.newBuilder()
								.uri(URI.create("https://api.github.com/repos/CFC-Servers/GLuaTest/actions/workflows/check_for_updates.yml/dispatches"))
								.header("Accept", "application/vnd.github+json")
								.header("Authorization", "Bearer " + (String)DataCache.Get("apiKey"))
								.header("X-GitHub-Api-Version", "2022-11-28")
								.header("Content-Type", "application/json")
								.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
								.build();
							
						HttpResponse<String> githubResponse = client.send(githunRequest, HttpResponse.BodyHandlers.ofString());
						if (githubResponse.statusCode() == 204)
						{
							System.out.println("Successfully dispatched GLuaTest workflow for all branches :3");
						}
					}
				}
				DataCache.Set("lastUpdates", lastUpdates);
				DataCache.Save();
			} catch (Exception e) {
				System.err.println("Error during request: " + e.getMessage());
				e.printStackTrace();
			}
		}, 0, 1, TimeUnit.MINUTES);
	}
}