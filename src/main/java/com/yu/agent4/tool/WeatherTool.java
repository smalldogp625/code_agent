package com.yu.agent4.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.Function;

public class WeatherTool {

    //定义输入
    public static record WeatherInput(@ToolParam(description = "查询指定城市的天气") String city){}

    //定义函数作用
    public static class WeatherFunction implements Function<WeatherInput, String> {
        private static final Map<String, String> WEATHER_DATA = Map.of(
                "北京", "25°C, 晴",
                "上海", "28°C, 多云",
                "广州", "32°C, 阵雨",
                "深圳", "30°C, 阴",
                "成都", "22°C, 小雨",
                "London", "15°C, cloudy",
                "Tokyo", "20°C, partly cloudy",
                "New York", "18°C, sunny");
        @Override
        public String apply(WeatherInput weatherInput) {
            String city = weatherInput.city;
            String weather = WEATHER_DATA.get(city);

            if (weather != null) {
                return "%s的天气是：%s".formatted(city, weather);
            }

            return "%s当前的天气查询还不支持".formatted(city);
        }
    }
    //定义builder
    public static Builder builer(){
        return new Builder();
    }
    public static class Builder{
        private Builder() {
        }
        public ToolCallback build(){
            return FunctionToolCallback.builder("Weather", new WeatherFunction())
                    .description("查询指定城市的天气（模拟数据）")
                    .inputType(WeatherInput.class)
                    .build();
        }
    }
}
