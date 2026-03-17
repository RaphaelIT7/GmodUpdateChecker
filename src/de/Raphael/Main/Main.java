package de.Raphael.Main;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
		
		if (!DataCache.Has("appID"))
		{
			DataCache.Set("appID", "4000"); // Normally can be 4000 or 4020
		}
		
		if (!DataCache.Has("updateFrequency"))
		{
			DataCache.Set("updateFrequency", 60);
		}
		
		if (!DataCache.Has("singleBranchTriggers"))
		{
			DataCache.Set("singleBranchTriggers", new ArrayList<String>());
		}
		
		if (!DataCache.Has("allBranchesTriggers"))
		{
			DataCache.Set("allBranchesTriggers", new ArrayList<String>());
		}
	}
	
	private static final HttpClient client = HttpClient.newHttpClient();
	private static final ObjectMapper mapper = new ObjectMapper();
	public static void main(String[] args)
	{
		System.out.println("Started");
		CreateDefaultsIfMissing();
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
									
							JSONArray singleBranchTriggers = (JSONArray)DataCache.Get("singleBranchTriggers");
							for (int j = 0; j < singleBranchTriggers.length(); j++)
							{
								JSONObject trigger = (JSONObject)singleBranchTriggers.getJSONObject(j);
								
								// Dispatching GLuaTest workflow
								JSONObject inputs = trigger.has("inputs") ? trigger.getJSONObject("inputs") : new JSONObject();
								for (String key : inputs.keySet())
								{
									if (inputs.getString(key).equalsIgnoreCase("$BRANCH"))
									{
										inputs.put(key, branch);
									}
									
									if (inputs.getString(key).equalsIgnoreCase("$VERSION"))
									{
										inputs.put(key, lastUpdate);
									}
								}
								
								JSONObject body = new JSONObject();
								body.put("ref", trigger.getString("branch"));
								body.put("inputs", inputs);
									
								HttpRequest githunRequest = HttpRequest.newBuilder()
										.uri(URI.create(trigger.getString("url")))
										.header("Accept", "application/vnd.github+json")
										.header("Authorization", "Bearer " + (String)trigger.getString("api"))
										.header("X-GitHub-Api-Version", "2022-11-28")
										.header("Content-Type", "application/json")
										.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
										.build();
										
								HttpResponse<String> githubResponse = client.send(githunRequest, HttpResponse.BodyHandlers.ofString());
								if (githubResponse.statusCode() == 204)
								{
									System.out.println("Successfully dispatched " + trigger.getString("name") + " workflow for " + branch + " :3");
								}
							}
						}
					}
					
					if (hasUpdate)
					{
						JSONArray allBranchesTriggers = (JSONArray)DataCache.Get("allBranchesTriggers");
						for (int i = 0; i < allBranchesTriggers.length(); i++)
						{
							JSONObject trigger = (JSONObject)allBranchesTriggers.getJSONObject(i);
						
							// Dispatching GLuaTest workflow
							JSONObject body = new JSONObject();
							body.put("ref", trigger.getString("branch"));
							body.put("inputs", trigger.has("inputs") ? trigger.getJSONObject("inputs") : new JSONObject());
							
							HttpRequest githunRequest = HttpRequest.newBuilder()
									.uri(URI.create(trigger.getString("url")))
									.header("Accept", "application/vnd.github+json")
									.header("Authorization", "Bearer " + (String)trigger.getString("api"))
									.header("X-GitHub-Api-Version", "2022-11-28")
									.header("Content-Type", "application/json")
									.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
									.build();
								
							HttpResponse<String> githubResponse = client.send(githunRequest, HttpResponse.BodyHandlers.ofString());
							if (githubResponse.statusCode() == 204)
							{
								System.out.println("Successfully dispatched " + trigger.getString("name") + " workflow for all branches :3");
							}
						}
					}
				}
				DataCache.Set("lastUpdates", lastUpdates);
				DataCache.Save();
			} catch (Exception e) {
				System.err.println("Error during request: " + e.getMessage());
				e.printStackTrace();
			}
		}, 0, (Integer)DataCache.Get("updateFrequency"), TimeUnit.SECONDS);
	}
}