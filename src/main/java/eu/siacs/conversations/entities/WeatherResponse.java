package eu.siacs.conversations.entities;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;

public class WeatherResponse {

    @SerializedName("list")
    public ArrayList<City> list;
}
