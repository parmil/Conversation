package eu.siacs.conversations.entities;

import com.google.gson.annotations.SerializedName;

public class City {
    @SerializedName("name")
    public String name;

    @SerializedName("main")
    public weather weather;
}
