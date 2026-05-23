package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.HapticFeedbackConstants;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private View _keyboard_content_view;
  private View _drag_handle;
  private ImageView _resize_handle;
  private View _toolbar;
  private View _btn_toggle_floating;
  private View _btn_toggle_split;
  private View _btn_collapse;
  private View _btn_settings;
  private View _btn_brightness;
  private View _btn_clipboard;
  private View _btn_reset_floating;
  private View _brightness_slider_container;
  private SeekBar _brightness_seekbar;
  private TextView _brightness_value_text;
  private View _btn_close_brightness;
  private View _keyboard_collapsed_icon;
  private View _bottom_controls;
  private View _dock_indicator;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private DeviceLocales _device_locales;
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboard_layout_view.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    _keyeventhandler = new KeyEventHandler(this.new Receiver(), _config);
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _keyboard_content_view = _keyboard_container_view.findViewById(R.id.keyboard_content);
    _toolbar = _keyboard_container_view.findViewById(R.id.toolbar);
    _btn_toggle_floating = _keyboard_container_view.findViewById(R.id.btn_toggle_floating);
    _btn_toggle_split = _keyboard_container_view.findViewById(R.id.btn_toggle_split);
    _btn_collapse = _keyboard_container_view.findViewById(R.id.btn_collapse);
    _btn_settings = _keyboard_container_view.findViewById(R.id.btn_settings);
    _btn_brightness = _keyboard_container_view.findViewById(R.id.btn_brightness);
    _btn_clipboard = _keyboard_container_view.findViewById(R.id.btn_clipboard);
    _btn_reset_floating = _keyboard_container_view.findViewById(R.id.btn_reset_floating);
    _brightness_slider_container = _keyboard_container_view.findViewById(R.id.brightness_slider_container);
    _brightness_seekbar = _keyboard_container_view.findViewById(R.id.brightness_seekbar);
    _brightness_value_text = _keyboard_container_view.findViewById(R.id.brightness_value);
    _btn_close_brightness = _keyboard_container_view.findViewById(R.id.btn_close_brightness);
    _keyboard_collapsed_icon = _keyboard_container_view.findViewById(R.id.keyboard_collapsed_icon);
    _bottom_controls = _keyboard_container_view.findViewById(R.id.bottom_controls);
    _drag_handle = _keyboard_container_view.findViewById(R.id.drag_handle);
    _resize_handle = (ImageView)_keyboard_container_view.findViewById(R.id.resize_handle);
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);
    _dock_indicator = _keyboard_container_view.findViewById(R.id.dock_indicator);
    setup_drag_handle();
    setup_resize_handle();
    setup_toolbar();
    setup_brightness_slider();
    setup_toolbar_drag();
    setup_collapsed_drag();
  }

  private void setup_brightness_slider()
  {
    _brightness_seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
    {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
      {
        if (fromUser)
        {
          _config.set_keyboard_brightness(progress);
          _brightness_value_text.setText(progress + "%");
          
          int opacity = _config.keyboardOpacity;
          if (_config.floating_enabled) opacity = (int)(opacity * 0.9);
          if (_keyboard_content_view.getBackground() != null)
            _keyboard_content_view.getBackground().setAlpha(opacity);
          if (_toolbar.getBackground() != null)
            _toolbar.getBackground().setAlpha(opacity);
          if (_brightness_slider_container.getBackground() != null)
            _brightness_slider_container.getBackground().setAlpha(opacity);
          if (_candidates_view.getBackground() != null)
            _candidates_view.getBackground().setAlpha(opacity);
          
          if (_drag_handle.getBackground() != null)
            _drag_handle.getBackground().setAlpha(opacity);
          _resize_handle.setImageAlpha(opacity);
          
          _keyboard_layout_view.refresh_alpha();
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar)
      {
         refresh_config();
      }
    });

    _btn_close_brightness.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        _brightness_slider_container.setVisibility(View.GONE);
        refresh_candidates_view();
      }
    });
  }

  private void setup_collapsed_drag()
  {
    _keyboard_collapsed_icon.setOnTouchListener(new View.OnTouchListener()
    {
      private float initialX, initialY;
      private int initialXPos, initialYPos;
      private long downTime;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        switch (event.getAction())
        {
          case MotionEvent.ACTION_DOWN:
            initialX = event.getRawX();
            initialY = event.getRawY();
            initialXPos = _config.floating_x;
            initialYPos = _config.floating_y;
            downTime = System.currentTimeMillis();
            return true;
          case MotionEvent.ACTION_MOVE:
            float dx = event.getRawX() - initialX;
            float dy = event.getRawY() - initialY;
            _config.floating_x = initialXPos + (int)dx;
            _config.floating_y = initialYPos + (int)dy;
            update_floating_position();
            return true;
          case MotionEvent.ACTION_UP:
            if (System.currentTimeMillis() - downTime < 200) {
               v.performClick();
            } else {
               _config.set_floating_position(_config.floating_x, _config.floating_y);
            }
            return true;
        }
        return false;
      }
    });
  }

  private void setup_toolbar_drag()
  {
    _toolbar.setOnTouchListener(new View.OnTouchListener()
    {
      private float initialX, initialY;
      private int initialXPos, initialYPos;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        if (!_config.floating_enabled) return false;
        
        switch (event.getAction())
        {
          case MotionEvent.ACTION_DOWN:
            initialX = event.getRawX();
            initialY = event.getRawY();
            initialXPos = _config.floating_x;
            initialYPos = _config.floating_y;
            return true;
          case MotionEvent.ACTION_MOVE:
            float dx = event.getRawX() - initialX;
            float dy = event.getRawY() - initialY;
            int newX = initialXPos + (int)dx;
            int newY = initialYPos + (int)dy;
            
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = _keyboard_content_view.getWidth();
            int h = _keyboard_content_view.getHeight();
            
            if (newX < 0) newX = 0;
            if (newX + w > dm.widthPixels) newX = dm.widthPixels - w;
            if (newY < 0) newY = 0;
            if (newY + h > dm.heightPixels) newY = dm.heightPixels - h;

            _config.floating_x = newX;
            _config.floating_y = newY;

            // Visual feedback for docking
            int dockingThreshold = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, dm);
            if (event.getRawY() > dm.heightPixels - dockingThreshold)
            {
               _dock_indicator.setVisibility(View.VISIBLE);
            }
            else
            {
               _dock_indicator.setVisibility(View.GONE);
            }

            update_floating_position();
            return true;
          case MotionEvent.ACTION_UP:
            _dock_indicator.setVisibility(View.GONE);
            DisplayMetrics dm_up = getResources().getDisplayMetrics();
            int dockingThreshold_up = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, dm_up);
            if (event.getRawY() > dm_up.heightPixels - dockingThreshold_up)
            {
               _config.set_floating_enabled(false);
               refresh_config();
            }
            else
            {
               _config.set_floating_position(_config.floating_x, _config.floating_y);
            }
            return true;
        }
        return false;
      }
    });
  }

  private void setup_toolbar()
  {
    _btn_toggle_floating.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        _config.set_floating_enabled(!_config.floating_enabled);
        refresh_config();
      }
    });

    _btn_toggle_split.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        _config.set_split_enabled(!_config.split_enabled);
        refresh_config();
      }
    });

    _btn_collapse.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int iconSize = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, dm);
        
        // Ensure it's exactly on the left edge and vertically centered
        _config.floating_x = 0;
        
        // Use the height of the container if possible, otherwise dm
        int containerHeight = _keyboard_container_view.getHeight();
        if (containerHeight <= 0) containerHeight = dm.heightPixels;
        
        _config.floating_y = (containerHeight - iconSize) / 2;

        _config.set_floating_position(_config.floating_x, _config.floating_y);
        _config.set_collapsed_enabled(true);
        refresh_config();
      }
    });

    _keyboard_collapsed_icon.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        _config.set_collapsed_enabled(false);
        refresh_config();
      }
    });

    _btn_settings.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        start_activity(SettingsActivity.class);
      }
    });

    _btn_brightness.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        _brightness_slider_container.setVisibility(View.VISIBLE);
        int percent = _config.get_keyboard_brightness_percent();
        _brightness_seekbar.setProgress(percent);
        _brightness_value_text.setText(percent + "%");
        refresh_candidates_view();
      }
    });

    _btn_clipboard.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (_clipboard_pane == null)
          _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
        setInputView(_clipboard_pane);
      }
    });

    _btn_reset_floating.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = _keyboard_container_view.getWidth() > 0 ? _keyboard_container_view.getWidth() : dm.widthPixels;
        int screenHeight = _keyboard_container_view.getHeight() > 0 ? _keyboard_container_view.getHeight() : dm.heightPixels;
        
        int w = (int) (screenWidth * 0.75);
        int x = (screenWidth - w) / 2;
        int y = (int) (screenHeight * 0.75);
        
        _config.save_floating_rect(x, y, w, 0); // 0 height = default wrap
        refresh_config();
      }
    });
  }

  private void setup_drag_handle()
  {
    _drag_handle.setOnTouchListener(new View.OnTouchListener()
    {
      private float initialX, initialY;
      private int initialXPos, initialYPos;
      private boolean docking_alerted = false;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        GradientDrawable bg = (GradientDrawable)_keyboard_content_view.getBackground();
        switch (event.getAction())
        {
          case MotionEvent.ACTION_DOWN:
            initialX = event.getRawX();
            initialY = event.getRawY();
            initialXPos = _config.floating_x;
            initialYPos = _config.floating_y;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (bg != null) bg.setStroke(4, 0xFF3399FF); // Highlight border
            return true;
          case MotionEvent.ACTION_MOVE:
            float dx = event.getRawX() - initialX;
            float dy = event.getRawY() - initialY;
            int newX = initialXPos + (int)dx;
            int newY = initialYPos + (int)dy;

            // Constrain to screen bounds
            int w = _keyboard_content_view.getWidth();
            int h = _keyboard_content_view.getHeight();
            
            if (newX < 0) newX = 0;
            if (newX + w > dm.widthPixels) newX = dm.widthPixels - w;
            if (newY < 0) newY = 0;
            if (newY + h > dm.heightPixels) newY = dm.heightPixels - h;

            _config.floating_x = newX;
            _config.floating_y = newY;

            // Visual feedback for docking
            int dockingThreshold = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, dm);
            if (event.getRawY() > dm.heightPixels - dockingThreshold)
            {
               _dock_indicator.setVisibility(View.VISIBLE);
               if (!docking_alerted)
               {
                 v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                 docking_alerted = true;
               }
            }
            else
            {
               _dock_indicator.setVisibility(View.GONE);
               docking_alerted = false;
            }

            update_floating_position();
            return true;
          case MotionEvent.ACTION_UP:
            if (bg != null) bg.setStroke(2, 0x33888888); // Reset border
            _dock_indicator.setVisibility(View.GONE);
            // Docking check: if near the bottom, disable floating
            int dockingThreshold_up = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, dm);
            if (event.getRawY() > dm.heightPixels - dockingThreshold_up)
            {
               _config.set_floating_enabled(false);
               refresh_config();
            }
            else
            {
               _config.set_floating_position(_config.floating_x, _config.floating_y);
            }
            return true;
        }
        return false;
      }
    });
  }

  private void setup_resize_handle()
  {
    _resize_handle.setOnTouchListener(new View.OnTouchListener()
    {
      private float initialRawX, initialRawY;
      private int initialWidth, initialHeight;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        GradientDrawable bg = (GradientDrawable)_keyboard_content_view.getBackground();
        switch (event.getAction())
        {
          case MotionEvent.ACTION_DOWN:
            initialRawX = event.getRawX();
            initialRawY = event.getRawY();
            initialWidth = _keyboard_content_view.getWidth();
            initialHeight = _keyboard_content_view.getHeight();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (bg != null) bg.setStroke(4, 0xFF3399FF); // Highlight border
            return true;
          case MotionEvent.ACTION_MOVE:
            float dx = event.getRawX() - initialRawX;
            float dy = event.getRawY() - initialRawY;
            int newWidth = initialWidth + (int)dx;
            int newHeight = initialHeight + (int)dy;
            
            // Minimal protection against negative sizes which cause crashes
            if (newWidth < 10) newWidth = 10;
            if (newHeight < 10) newHeight = 10;

            updateLayoutWidthOf(_keyboard_content_view, newWidth);
            _config.floating_width = newWidth;
            
            updateLayoutHeightOf(_keyboard_content_view, newHeight);
            _config.floating_height = newHeight;

            if (_keyboard_layout_view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)_keyboard_layout_view.getLayoutParams();
                lp.height = 0;
                lp.weight = 1.0f;
                _keyboard_layout_view.setLayoutParams(lp);
            }

            _keyboard_content_view.requestLayout();
            _keyboard_layout_view.requestLayout();
            update_floating_position(newWidth, newHeight);
            return true;
          case MotionEvent.ACTION_UP:
            if (bg != null) bg.setStroke(2, 0x33888888); // Reset border
            _config.save_floating_rect(_config.floating_x, _config.floating_y, _config.floating_width, _config.floating_height);
            return true;
        }
        return false;
      }
    });
  }

  private void update_floating_position()
  {
      update_floating_position(-1, -1);
  }

  private void update_floating_position(int overrideWidth, int overrideHeight)
  {
    DisplayMetrics dm = getResources().getDisplayMetrics();
    int screenWidth = dm.widthPixels;
    int screenHeight = dm.heightPixels;
    
    // Use container dimensions if available for better accuracy, 
    // but avoid using them if they are likely representing only the keyboard area 
    // when we are in floating/collapsed mode.
    if (_keyboard_container_view.getWidth() > 0) screenWidth = _keyboard_container_view.getWidth();
    if (_keyboard_container_view.getHeight() > 0)
    {
       if (!_config.floating_enabled && !_config.collapsed_enabled)
       {
         screenHeight = _keyboard_container_view.getHeight();
       }
       else if (_keyboard_container_view.getHeight() > dm.heightPixels / 2)
       {
         // Only use container height in floating mode if it's large enough to be full-screen
         screenHeight = _keyboard_container_view.getHeight();
       }
    }

    if (_config.collapsed_enabled)
    {
       int w = _keyboard_collapsed_icon.getWidth();
       if (w <= 0) w = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, dm);
       int h = _keyboard_collapsed_icon.getHeight();
       if (h <= 0) h = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, dm);

       if (_config.floating_x < 0) _config.floating_x = 0;
       if (_config.floating_x + w > screenWidth) _config.floating_x = screenWidth - w;
       if (_config.floating_y < 0) _config.floating_y = 0;
       if (_config.floating_y + h > screenHeight) _config.floating_y = screenHeight - h;

       _keyboard_collapsed_icon.setX(_config.floating_x);
       _keyboard_collapsed_icon.setY(_config.floating_y);
       return;
    }

    if (_config.floating_enabled)
    {
      if (_config.floating_x == 0 && _config.floating_y == 0)
      {
        _config.floating_width = (int) (dm.widthPixels * 0.75);
        _config.floating_x = (dm.widthPixels - _config.floating_width) / 2;
        _config.floating_y = dm.heightPixels; // Will be clamped to the bottom
      }
      
      // Ensure it doesn't overflow the screen
      int w = overrideWidth > 0 ? overrideWidth : _keyboard_content_view.getWidth();
      if (w <= 0) w = _config.floating_width;
      if (w <= 0) w = (int) (screenWidth * 0.75);
      
      int h = overrideHeight > 0 ? overrideHeight : _keyboard_content_view.getHeight();
      if (h <= 0) h = _config.floating_height;
      if (h <= 0) h = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220, dm);
      
      if (_config.floating_x < 0) _config.floating_x = 0;
      if (_config.floating_x + w > screenWidth) _config.floating_x = screenWidth - w;
      if (_config.floating_y < 0) _config.floating_y = 0;
      if (_config.floating_y + h > screenHeight) _config.floating_y = screenHeight - h;

      _keyboard_content_view.setX(_config.floating_x);
      _keyboard_content_view.setY(_config.floating_y);
      _keyboard_content_view.requestLayout();
      _keyboard_container_view.requestApplyInsets();
    }
    else
    {
      _keyboard_content_view.setTranslationX(0);
      _keyboard_content_view.setTranslationY(0);
    }
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _device_locales = DeviceLocales.load(this);
    if (_device_locales.default_ != null)
    {
      String layout_name = _device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.current_dictionary = null;
    _config.emoji_dictionary = null;
    if (_device_locales.default_ == null)
      return;
    String current = _device_locales.default_.dictionary;
    if (current == null)
      return;
    Cdict[] dicts = _dictionaries.load(current);
    if (dicts == null)
      return;
    _config.current_dictionary = Dictionaries.find_by_name(dicts, "main");
    _config.emoji_dictionary = Dictionaries.find_by_name(dicts, "emoji");
  }

  private void refresh_candidates_view()
  {
    if (_brightness_slider_container != null && _brightness_slider_container.getVisibility() == View.VISIBLE)
    {
      _candidates_view.setVisibility(View.GONE);
      _toolbar.setVisibility(View.GONE);
      return;
    }
    boolean should_show =
      _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && _candidates_view != null && _candidates_view.has_candidates();
    
    if (should_show)
    {
      _candidates_view.refresh_config(_config);
      _candidates_view.setVisibility(View.VISIBLE);
      if (_config.floating_enabled && _config.split_enabled)
          _candidates_view.setBackground(null);

      if (_toolbar != null) _toolbar.setVisibility(View.GONE);
    }
    else
    {
      if (_candidates_view != null) _candidates_view.setVisibility(View.GONE);
      if (_toolbar != null) _toolbar.setVisibility(View.VISIBLE);
    }
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    if (_config.floating_enabled) _config.marginTop = 0;
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background opacity
    int opacity = _config.keyboardOpacity;
    if (_keyboard_content_view.getBackground() != null)
      _keyboard_content_view.getBackground().setAlpha(opacity);
    if (_toolbar.getBackground() != null)
      _toolbar.getBackground().setAlpha(opacity);
    if (_brightness_slider_container.getBackground() != null)
      _brightness_slider_container.getBackground().setAlpha(opacity);
    if (_candidates_view.getBackground() != null)
      _candidates_view.getBackground().setAlpha(opacity);
    if (_drag_handle.getBackground() != null)
      _drag_handle.getBackground().setAlpha(opacity);
    _resize_handle.setImageAlpha(opacity);
    _drag_handle.setVisibility(_config.floating_enabled && !_config.collapsed_enabled ? View.VISIBLE : View.GONE);
    _resize_handle.setVisibility(_config.floating_enabled && !_config.collapsed_enabled ? View.VISIBLE : View.GONE);
    _btn_reset_floating.setVisibility(_config.floating_enabled && !_config.collapsed_enabled ? View.VISIBLE : View.GONE);
    _btn_toggle_split.setAlpha(_config.split_enabled ? 1.0f : 0.5f);
    _btn_toggle_floating.setAlpha(_config.floating_enabled ? 1.0f : 0.5f);
    _keyboard_collapsed_icon.setVisibility(_config.collapsed_enabled ? View.VISIBLE : View.GONE);

    if (_config.collapsed_enabled)
    {
       _keyboard_content_view.setVisibility(View.GONE);
       update_floating_position();
    }
    else
    {
       _keyboard_content_view.setVisibility(View.VISIBLE);
       if (_config.floating_enabled)
       {
         if (_config.split_enabled)
           _keyboard_content_view.setBackground(null);
         else
           _keyboard_content_view.setBackgroundResource(R.drawable.bg_floating_keyboard);

         int width = _config.floating_width;
         if (width <= 0) width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75);
         updateLayoutWidthOf(_keyboard_content_view, width);

         int height = _config.floating_height;
         if (height <= 0)
         {
             // Smaller default height in floating mode
             updateLayoutHeightOf(_keyboard_content_view, ViewGroup.LayoutParams.WRAP_CONTENT);
             updateLayoutHeightOf(_keyboard_layout_view, (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180, getResources().getDisplayMetrics()));
             if (_keyboard_layout_view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                 LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)_keyboard_layout_view.getLayoutParams();
                 lp.weight = 0;
                 _keyboard_layout_view.setLayoutParams(lp);
             }
         }
         else
         {
             updateLayoutHeightOf(_keyboard_content_view, height);
             updateLayoutHeightOf(_keyboard_layout_view, 0);
             if (_keyboard_layout_view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                 LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)_keyboard_layout_view.getLayoutParams();
                 lp.weight = 1.0f;
                 _keyboard_layout_view.setLayoutParams(lp);
             }
         }
       }
       else if (_config.split_enabled)
       {
         _keyboard_content_view.setBackground(null);
         _keyboard_content_view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
         _keyboard_content_view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;

         _keyboard_layout_view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
         if (_keyboard_layout_view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
             ((LinearLayout.LayoutParams)_keyboard_layout_view.getLayoutParams()).weight = 0;
         }
       }
       else
       {
         TypedValue typedValue = new TypedValue();
         new ContextThemeWrapper(this, _config.theme).getTheme().resolveAttribute(R.attr.colorKeyboard, typedValue, true);
         _keyboard_content_view.setBackgroundColor(typedValue.data);
         _keyboard_content_view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
         _keyboard_content_view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;

         _keyboard_layout_view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
         if (_keyboard_layout_view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
             ((LinearLayout.LayoutParams)_keyboard_layout_view.getLayoutParams()).weight = 0;
         }
       }
    }
    update_floating_position();
    updateSoftInputWindowLayoutParams();
    _keyboard_layout_view.reset();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN: return loadPinentry(R.xml.pin);
        case NUMBER: return loadNumpad(R.xml.numeric);
      }
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    _config.editor_config.refresh(info, getResources());
    refresh_config();
    _currentSpecialLayout = refresh_special_layout();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.started(_config);
    setInputView(_keyboard_container_view);
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }

    final View inputArea = window.findViewById(android.R.id.inputArea);
    if (inputArea == null) return;
    final View inputAreaParent = (View) inputArea.getParent();

    if (_config.floating_enabled || _config.collapsed_enabled)
    {
      window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
      window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      inputArea.setBackground(null);
      inputAreaParent.setBackground(null);
      updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutWidthOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(inputAreaParent, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutWidthOf(inputAreaParent, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(inputArea, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutWidthOf(inputArea, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(_keyboard_container_view, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutWidthOf(_keyboard_container_view, ViewGroup.LayoutParams.MATCH_PARENT);

      updateLayoutGravityOf(inputAreaParent, Gravity.TOP | Gravity.START);
      updateLayoutGravityOf(inputArea, Gravity.TOP | Gravity.START);
      updateLayoutGravityOf(_keyboard_container_view, Gravity.TOP | Gravity.START);
      
      _keyboard_container_view.setPadding(0, 0, 0, 0);
    }
    else
    {
      updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutWidthOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(
              inputAreaParent,
              isFullscreenMode()
                      ? ViewGroup.LayoutParams.MATCH_PARENT
                      : ViewGroup.LayoutParams.WRAP_CONTENT);
      updateLayoutWidthOf(inputAreaParent, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(inputArea, ViewGroup.LayoutParams.WRAP_CONTENT);
      updateLayoutWidthOf(inputArea, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutHeightOf(_keyboard_container_view, ViewGroup.LayoutParams.WRAP_CONTENT);
      updateLayoutWidthOf(_keyboard_container_view, ViewGroup.LayoutParams.MATCH_PARENT);
      updateLayoutGravityOf(inputAreaParent, Gravity.BOTTOM);
    }
  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutWidthOf(final Window window, final int layoutWidth) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.width != layoutWidth) {
      params.width = layoutWidth;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutWidthOf(final View view, final int layoutWidth) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.width != layoutWidth) {
      params.width = layoutWidth;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onComputeInsets(InputMethodService.Insets outInsets)
  {
    super.onComputeInsets(outInsets);
    if (_config.collapsed_enabled)
    {
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      outInsets.contentTopInsets = screenHeight;
      outInsets.visibleTopInsets = screenHeight;
      outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
      int x = (int)_keyboard_collapsed_icon.getX();
      int y = (int)_keyboard_collapsed_icon.getY();
      int w = _keyboard_collapsed_icon.getWidth();
      int h = _keyboard_collapsed_icon.getHeight();
      outInsets.touchableRegion.set(x, y, x + w, y + h);
    }
    else if (_config.floating_enabled)
    {
      // In floating mode, we don't want the keyboard to push the app's content at all.
      // Setting these insets to the full screen height tells the system the keyboard
      // is effectively "below" the screen as far as the app's layout is concerned.
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      outInsets.contentTopInsets = screenHeight;
      outInsets.visibleTopInsets = screenHeight;
      
      outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
      
      // Calculate coordinates relative to the root view (which is full screen)
      int x = (int)_keyboard_content_view.getX();
      int y = (int)_keyboard_content_view.getY();
      int w = _keyboard_content_view.getWidth();
      int h = _keyboard_content_view.getHeight();
      
      if (_config.split_enabled)
      {
          int sideWidth = (int)(w * 0.35f);
          int gapWidth = (int)(w * 0.3f);
          outInsets.touchableRegion.set(x, y, x + sideWidth, y + h);
          outInsets.touchableRegion.op(x + sideWidth + gapWidth, y, x + w, y + h, Region.Op.UNION);
          // Also include toolbar and candidates if they are full width
          outInsets.touchableRegion.op(x, y, x + w, y + _toolbar.getBottom(), Region.Op.UNION);
          // And include the bottom navigation/drag handle area
          outInsets.touchableRegion.op(x, y + _bottom_controls.getTop(), x + w, y + h, Region.Op.UNION);
      }
      else
      {
          outInsets.touchableRegion.set(x, y, x + w, y + h);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.ime_subtype_changed();
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override
  public void onUpdateExtractingVisibility(EditorInfo ei)
  {
    ei.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
    super.onUpdateExtractingVisibility(ei);
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    super.onEvaluateInputViewShown();
    // Return true regardless of the super call result to fix the keyboard not
    // being visible on Android 16
    return true;
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    start_activity(DictionariesActivity.class);
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboard_layout_view.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumpad(R.xml.numeric));
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboard_container_view);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case TOGGLE_FLOATING:
          _config.set_floating_enabled(!_config.floating_enabled);
          refresh_config();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
      refresh_candidates_view();
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
}
