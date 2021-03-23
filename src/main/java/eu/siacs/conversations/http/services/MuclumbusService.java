package eu.siacs.conversations.http.services;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.entities.Room;
import eu.siacs.conversations.entities.WeatherResponse;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface MuclumbusService {

    @GET("/api/1.0/rooms/unsafe")
    Call<Rooms> getRooms(@Query("p") int page);

    @GET("http://api.openweathermap.org/data/2.5/find?lat=28.70&lon=77.10&cnt=10&appid=355f05a4c67796c2ae28dea908e72c2c")
    Call<WeatherResponse> getWeatherInfo();

    @POST("/api/1.0/search")
    Call<SearchResult> search(@Body SearchRequest searchRequest);

    class Rooms {
        int page;
        int total;
        int pages;
        public List<Room> items;
    }

    class SearchRequest {

        public final Set<String> keywords;

        public SearchRequest(String keyword) {
            this.keywords = Collections.singleton(keyword);
        }
    }

    class SearchResult {

        public Result result;

    }

    class Result {

        public List<Room> items;

    }

}
