package eu.siacs.conversations.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.FragmentWeatherBinding;
import eu.siacs.conversations.entities.WeatherResponse;
import eu.siacs.conversations.services.ChannelDiscoveryService;
import eu.siacs.conversations.ui.adapter.WeatherAdapter;

public class WeatherFragment extends XmppFragment implements ChannelDiscoveryService.OnWeather {
    private FragmentWeatherBinding binding;
    private XmppActivity activity;
    private WeatherAdapter weatherAdapter;
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        this.binding = DataBindingUtil.inflate(inflater, R.layout.fragment_weather, container, false);
        return binding.getRoot();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof XmppActivity) {
            this.activity = (XmppActivity) activity;
        } else {
            throw new IllegalStateException("Trying to attach fragment to activity that is not an XmppActivity");
        }
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        weatherAdapter = new WeatherAdapter();
        binding.cityList.setAdapter(weatherAdapter);
        activity.xmppConnectionService.getWeather(this);
    }

    @Override
    void refresh() { }

    @Override
    public void onBackendConnected() {}

    @Override
    public void onWeatherResultsFound(WeatherResponse results) {
        runOnUiThread(() -> {
            binding.progress.setVisibility(View.GONE);
            if (results != null && results.list != null) {
                weatherAdapter.addCityList(results.list);
                binding.cityList.getAdapter().notifyDataSetChanged();
            }
        });
    }
}
