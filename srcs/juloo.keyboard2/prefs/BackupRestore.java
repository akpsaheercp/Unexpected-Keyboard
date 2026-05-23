package juloo.keyboard2.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import juloo.keyboard2.R;
import org.json.JSONArray;
import org.json.JSONObject;

public class BackupRestore
{
  public static void backup(Context context, Uri uri)
  {
    try (OutputStream out = context.getContentResolver().openOutputStream(uri))
    {
      SharedPreferences prefs = context.getSharedPreferences(
          context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
      Map<String, ?> allEntries = prefs.getAll();
      JSONObject json = new JSONObject();

      for (Map.Entry<String, ?> entry : allEntries.entrySet())
      {
        Object value = entry.getValue();
        JSONObject item = new JSONObject();
        if (value instanceof Set)
        {
          JSONArray array = new JSONArray();
          for (Object s : (Set<?>) value) { array.put(s); }
          item.put("type", "set");
          item.put("value", array);
        }
        else
        {
          item.put("value", value == null ? JSONObject.NULL : value);
          if (value instanceof Boolean) item.put("type", "bool");
          else if (value instanceof Integer) item.put("type", "int");
          else if (value instanceof Long) item.put("type", "long");
          else if (value instanceof Float) item.put("type", "float");
          else if (value instanceof String) item.put("type", "string");
        }
        json.put(entry.getKey(), item);
      }

      out.write(json.toString(2).getBytes());
      Toast.makeText(context, R.string.backup_success, Toast.LENGTH_SHORT).show();
    }
    catch (Exception e)
    {
      Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_SHORT).show();
    }
  }

  public static void restore(Context context, Uri uri)
  {
    try (InputStream in = context.getContentResolver().openInputStream(uri))
    {
      java.util.Scanner s = new java.util.Scanner(in).useDelimiter("\\A");
      String jsonString = s.hasNext() ? s.next() : "";
      JSONObject json = new JSONObject(jsonString);

      SharedPreferences prefs = context.getSharedPreferences(
          context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
      SharedPreferences.Editor editor = prefs.edit();
      editor.clear();

      java.util.Iterator<String> keys = json.keys();
      while (keys.hasNext())
      {
        String key = keys.next();
        Object obj = json.get(key);
        if (!(obj instanceof JSONObject)) continue;
        JSONObject item = (JSONObject) obj;
        String type = item.optString("type");
        Object value = item.get("value");

        switch (type)
        {
          case "bool": editor.putBoolean(key, (Boolean) value); break;
          case "int": editor.putInt(key, (Integer) value); break;
          case "long": editor.putLong(key, ((Number) value).longValue()); break;
          case "float": editor.putFloat(key, ((Number) value).floatValue()); break;
          case "string": editor.putString(key, (String) value); break;
          case "set":
            JSONArray array = (JSONArray) value;
            java.util.HashSet<String> set = new java.util.HashSet<>();
            for (int i = 0; i < array.length(); i++) { set.add(array.getString(i)); }
            editor.putStringSet(key, set);
            break;
        }
      }

      editor.apply();
      Toast.makeText(context, R.string.restore_success, Toast.LENGTH_LONG).show();
    }
    catch (Exception e)
    {
      Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show();
    }
  }
}
