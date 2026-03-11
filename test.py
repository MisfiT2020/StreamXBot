from youtube_search import YoutubeSearch

results = YoutubeSearch('search terms', max_results=10).to_json()

print(results)

# returns a json string

########################################

results = YoutubeSearch('Glitch In The Simulation', max_results=1).to_dict()

print(results)
# returns a dictionary