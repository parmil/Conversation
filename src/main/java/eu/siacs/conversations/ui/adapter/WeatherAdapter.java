package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemWeatherBinding;
import eu.siacs.conversations.entities.City;

public class WeatherAdapter extends RecyclerView.Adapter<WeatherAdapter.WeatherViewHolder> {
    private final ArrayList<City> cityList = new ArrayList<>();

     @NonNull
    @Override
    public WeatherViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        ItemWeatherBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.item_weather, parent, false);
        return new WeatherViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull WeatherViewHolder holder, int position) {
         holder.binding.setCity( cityList.get(position));
    }

    public void addCityList(List<City> list) {
        this.cityList.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return cityList.size();
    }

    static class WeatherViewHolder extends RecyclerView.ViewHolder {
        private final ItemWeatherBinding binding;
        WeatherViewHolder(ItemWeatherBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

}
